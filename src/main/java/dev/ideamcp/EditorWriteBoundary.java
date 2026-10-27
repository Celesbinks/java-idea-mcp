package dev.ideamcp;

import com.google.gson.*;
import com.intellij.concurrency.IntelliJContextElement;
import com.intellij.concurrency.ThreadContext;
import com.intellij.openapi.application.*;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.newvfs.ManagingFS;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.util.messages.MessageBusConnection;
import kotlin.coroutines.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

import static dev.ideamcp.protocol.Json.object;

/** Operation-scoped editor tracking. No source text or Document survives completion. */
final class EditorWriteBoundary implements AutoCloseable {
    static final String META_KEY = "dev.ideamcp/editorWrite";
    static final Set<String> TOOLS = Set.of("create_new_file", "apply_patch", "reformat_file", "rename_refactoring");
    private static final Map<Project, EditorWriteBoundary> ACTIVE = new WeakHashMap<>();
    private static final int FILE_LIMIT = 10_000;
    private final Project project;
    private final boolean exactTargets;
    private final Set<VirtualFile> files = new LinkedHashSet<>();
    private final Map<VirtualFile, Long> fileVersions = new HashMap<>();
    private final Set<VirtualFile> concurrentFiles = new HashSet<>();
    private final Map<Document, Change> documents = new IdentityHashMap<>();
    private final Set<Path> requested = new LinkedHashSet<>();
    private MessageBusConnection connection;
    private final DocumentListener listener;
    private volatile boolean overflow;
    private volatile boolean closed;
    private record Change(boolean concurrent) {}
    static final class Busy extends IllegalStateException {
        Busy() { super("EDITOR_WRITE_BUSY: wait for this project's active editor operation; do not retry edits blindly"); }
    }
    static final class Rejected extends IllegalStateException {
        Rejected(String code) { super(code + ": reconcile the target editor/disk state before editing; no automatic overwrite"); }
    }

    static boolean tracked(String name) { return TOOLS.contains(name) || name.equals("execute_tool"); }
    final class Context extends AbstractCoroutineContextElement implements IntelliJContextElement {
        Context() { super(KEY); }
        EditorWriteBoundary boundary() { return EditorWriteBoundary.this; }
        @Override public IntelliJContextElement produceChildElement(CoroutineContext parentContext, boolean structured) {
            // IDEA format/refactor processors use legacy (unstructured) callbacks.
            // Keep attribution across those callbacks without installing thread state.
            return closed ? null : this;
        }
    }
    private static final CoroutineContext.Key<EditorWriteBoundary.Context> KEY = new CoroutineContext.Key<>() {};
    CoroutineContext context() { return new Context(); }
    static boolean maySave(Document document) {
        CoroutineContext context = ThreadContext.currentThreadContextOrNull();
        Context mark = context == null ? null : context.get(KEY);
        if (mark == null) return true; // User saves and other operations are untouched.
        EditorWriteBoundary boundary = mark.boundary();
        if (boundary.closed) return true;
        synchronized (boundary) {
            Change change = boundary.documents.get(document);
            VirtualFile file = FileDocumentManager.getInstance().getFile(document);
            return change != null && !change.concurrent() && (!boundary.exactTargets
                    || (file != null && boundary.requested.contains(Path.of(file.getPath()).normalize())));
        }
    }
    private boolean current() {
        CoroutineContext context = ThreadContext.currentThreadContextOrNull();
        Context mark = context == null ? null : context.get(KEY);
        return !closed && mark != null && mark.boundary() == this;
    }

    EditorWriteBoundary(Project project, String name, JsonObject args) throws Exception {
        this(project, name, args, null);
    }
    EditorWriteBoundary(Project project, String name, JsonObject args, List<Path> validatedTargets) throws Exception {
        this.project = project;
        exactTargets = name.equals("create_new_file") || name.equals("reformat_file") || name.equals("apply_patch");
        synchronized (ACTIVE) {
            if (ACTIVE.containsKey(project)) throw new Busy();
            ACTIVE.put(project, this);
        }
        listener = new DocumentListener() {
            @Override public void documentChanged(DocumentEvent event) {
                synchronized (EditorWriteBoundary.this) {
                    var document = event.getDocument();
                    if (current()) {
                        if (documents.size() >= FILE_LIMIT && !documents.containsKey(document)) { overflow = true; return; }
                        Change previous = documents.get(document);
                        documents.put(document, new Change(previous != null && previous.concurrent()));
                        add(FileDocumentManager.getInstance().getFile(document), false);
                    } else if (documents.containsKey(document)) {
                        documents.put(document, new Change(true));
                    }
                }
            }
        };
        try {
            connection = ApplicationManager.getApplication().getMessageBus().connect();
            EditorFactory.getInstance().getEventMulticaster().addDocumentListener(listener);
            connection.subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
                @Override public void after(List<? extends VFileEvent> events) {
                    synchronized (EditorWriteBoundary.this) {
                        for (var event : events) {
                            VirtualFile file = event.getFile();
                            if (current()) add(file, true);
                            else if (file != null && files.contains(file) && fileVersions.get(file) != file.getModificationStamp()) concurrentFiles.add(file);
                        }
                    }
                }
            });
            if (validatedTargets != null) {
                requested.addAll(validatedTargets);
                if (requested.size() > FILE_LIMIT) throw new IllegalArgumentException("EDITOR_SAVE_SCOPE_LIMIT: too many requested files");
            } else {
                if (name.equals("create_new_file") || name.equals("rename_refactoring")) request(args.get("pathInProject"));
                if (name.equals("reformat_file")) for (var path : args.getAsJsonArray("files")) request(path);
            }
            if (!name.equals("create_new_file") || (args.has("overwrite") && args.get("overwrite").getAsBoolean())) preflight();
        } catch (Exception failure) { close(); throw failure; }
    }
    private void request(JsonElement value) {
        if (value == null) return;
        Path path = Path.of(value.getAsString());
        requested.add((path.isAbsolute() ? path : Path.of(Objects.requireNonNull(project.getBasePath())).resolve(path)).normalize());
        if (requested.size() > FILE_LIMIT) throw new IllegalArgumentException("EDITOR_SAVE_SCOPE_LIMIT: too many requested files");
    }
    private synchronized void add(VirtualFile file, boolean updatedByScope) {
        if (file == null) return;
        if (files.size() >= FILE_LIMIT && !files.contains(file)) { overflow = true; return; }
        files.add(file);
        if (updatedByScope) fileVersions.put(file, file.getModificationStamp());
        else fileVersions.putIfAbsent(file, file.getModificationStamp());
    }
    private synchronized boolean changedOutsideScope(VirtualFile file) {
        return concurrentFiles.contains(file) || !Objects.equals(fileVersions.get(file), file.getModificationStamp());
    }
    private void preflight() throws Exception {
        for (Path path : requested) {
            VirtualFile file = LocalFileSystem.getInstance().findFileByPathIfCached(path.toString().replace('\\', '/'));
            if (file == null || !file.isValid() || file.isDirectory()) continue;
            Document document = FileDocumentManager.getInstance().getCachedDocument(file);
            if (document != null && FileDocumentManager.getInstance().isDocumentUnsaved(document)) throw new Rejected("EDITOR_UNSAVED_DOCUMENT");
            ManagingFS.getInstance().flushPendingUpdates(file);
            if (!sameDisk(file)) throw new Rejected("EDITOR_DISK_CONFLICT");
        }
    }

    JsonObject finish(boolean providerSucceeded) {
        JsonArray issues = new JsonArray();
        try {
            if (providerSucceeded) {
                List<Path> paths;
                synchronized (this) { paths = List.copyOf(requested); }
                for (Path path : paths) add(LocalFileSystem.getInstance().findFileByNioFile(path), false);
            }
            List<VirtualFile> touched;
            Map<Document, Change> changed;
            synchronized (this) { touched = List.copyOf(files); changed = new IdentityHashMap<>(documents); }
            if (overflow) issues.add("EDITOR_SAVE_SCOPE_LIMIT");
            if (providerSucceeded) {
                PlatformOperationService.stage("saving");
                for (var entry : changed.entrySet()) {
                    Document document = entry.getKey();
                    VirtualFile file = FileDocumentManager.getInstance().getFile(document);
                    if (file == null || !file.isValid()) continue;
                    if (!FileDocumentManager.getInstance().isDocumentUnsaved(document)) continue;
                    if (entry.getValue().concurrent()) { issues.add("EDITOR_CHANGED_DURING_WRITE: document context"); continue; }
                    if (changedOutsideScope(file)) { issues.add("EDITOR_CHANGED_DURING_WRITE: VFS version"); continue; }
                    ManagingFS.getInstance().flushPendingUpdates(file);
                    if (!sameDisk(file)) { issues.add("EDITOR_DISK_CONFLICT"); continue; }
                    saveOwned(document);
                }
            }
            PlatformOperationService.stage("flushing");
            for (var file : touched) if (file.isValid() && !file.isDirectory()) ManagingFS.getInstance().flushPendingUpdates(file);
            PlatformOperationService.stage("verifying");
            for (var file : touched) {
                if (!file.isValid() || file.isDirectory()) continue; // Deletes/moves are completed by the provider's VFS operations.
                if (changedOutsideScope(file)) { issues.add("EDITOR_CHANGED_DURING_WRITE: VFS version"); continue; }
                long before = file.getModificationStamp();
                Document document = FileDocumentManager.getInstance().getCachedDocument(file);
                long stamp = document == null ? -1 : document.getModificationStamp();
                if (document != null && FileDocumentManager.getInstance().isDocumentUnsaved(document)) { issues.add("EDITOR_DOCUMENT_UNSAVED"); continue; }
                if (!sameDisk(file)) issues.add("EDITOR_DISK_CONFLICT");
                if (before != file.getModificationStamp() || (document != null && (stamp != document.getModificationStamp()
                        || FileDocumentManager.getInstance().isDocumentUnsaved(document)))) issues.add("EDITOR_CHANGED_DURING_VERIFY");
            }
            return object("status", providerSucceeded && issues.isEmpty() ? touched.isEmpty() ? "not_observed" : "persisted"
                            : !providerSucceeded && touched.isEmpty() ? "not_modified" : "incomplete",
                    "files", touched.size(), "issues", issues, "retrySafe", false);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            Throwable cause = failure instanceof ExecutionException && failure.getCause() != null ? failure.getCause() : failure;
            Set<String> known = Set.of("EDITOR_CHANGED_DURING_WRITE: document version", "EDITOR_CHANGED_DURING_WRITE: VFS version", "EDITOR_CONTEXT_DISPOSED", "EDITOR_SAVE_UNVERIFIED: no file");
            issues.add(cause instanceof IllegalStateException && known.contains(cause.getMessage())
                    ? cause.getMessage() : "EDITOR_SAVE_UNVERIFIED: " + cause.getClass().getSimpleName());
            return object("status", "incomplete", "files", files.size(), "issues", issues, "retrySafe", false);
        }
    }

    private void saveOwned(Document document) throws Exception {
        var result = new CompletableFuture<Void>();
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                WriteIntentReadAction.run(() -> {
                    VirtualFile file = FileDocumentManager.getInstance().getFile(document);
                    if (project.isDisposed()) throw new IllegalStateException("EDITOR_CONTEXT_DISPOSED");
                    // PSI commits may reset the Document stamp without a text change.
                    // A foreign text event is sticky; verify that ownership under write
                    // intent, then compare stable stamps/content after saving and flush.
                    synchronized (this) {
                        Change latest = documents.get(document);
                        if (latest == null || latest.concurrent()) throw new IllegalStateException("EDITOR_CHANGED_DURING_WRITE: document version");
                    }
                    if (file == null) throw new IllegalStateException("EDITOR_SAVE_UNVERIFIED: no file");
                    if (changedOutsideScope(file)) throw new IllegalStateException("EDITOR_CHANGED_DURING_WRITE: VFS version");
                    try (var ignored = ThreadContext.installThreadContext(ThreadContext.currentThreadContext().plus(context()), true)) {
                        FileDocumentManager.getInstance().saveDocument(document);
                    }
                });
                result.complete(null);
            } catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        // This is the operation worker, never an HTTP/EDT wait for the whole edit.
        for (;;) try { result.get(100, TimeUnit.MILLISECONDS); return; }
        catch (TimeoutException pending) {
            if (project.isDisposed() || ApplicationManager.getApplication().isDisposed())
                throw new IllegalStateException("EDITOR_CONTEXT_DISPOSED");
        }
    }

    private static boolean sameDisk(VirtualFile file) throws Exception {
        if (!file.getFileSystem().getProtocol().equals("file")) return false;
        Path path = Path.of(file.getPath());
        var before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.isSymbolicLink() || before.isOther()) return false;
        byte[] expected;
        // VirtualFile streams omit the BOM; physical streams include it. Include
        // the cached BOM explicitly so encoding changes are detected, not ignored.
        try (var input = file.getInputStream()) { expected = digest(input, file.getBOM()); }
        byte[] actual;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { actual = digest(input, null); }
        var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return MessageDigest.isEqual(expected, actual) && before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime()) && Objects.equals(before.fileKey(), after.fileKey());
    }
    private static byte[] digest(InputStream input, byte[] prefix) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        if (prefix != null) digest.update(prefix);
        byte[] buffer = new byte[16_384];
        for (int size; (size = input.read(buffer)) != -1;) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            digest.update(buffer, 0, size);
        }
        return digest.digest();
    }
    static JsonObject attach(JsonObject result, JsonObject state) {
        JsonObject meta = result.has("_meta") ? result.getAsJsonObject("_meta").deepCopy() : object();
        meta.add(META_KEY, state); result.add("_meta", meta);
        if (state.get("status").getAsString().equals("incomplete")) {
            result.addProperty("isError", true);
            result.getAsJsonArray("content").add(object("type", "text", "text", "EDITOR_WRITE_INCOMPLETE: inspect editorWrite and reconcile files before another edit; no automatic retry or overwrite"));
        }
        return result;
    }
    @Override public void close() {
        closed = true;
        try {
            EditorFactory.getInstance().getEventMulticaster().removeDocumentListener(listener);
            if (connection != null) connection.disconnect();
        } finally {
            synchronized (ACTIVE) { ACTIVE.remove(project, this); }
            synchronized (this) { files.clear(); fileVersions.clear(); concurrentFiles.clear(); documents.clear(); requested.clear(); }
        }
    }
}
