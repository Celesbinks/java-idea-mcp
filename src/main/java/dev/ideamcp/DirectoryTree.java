package dev.ideamcp;

import com.google.gson.JsonObject;
import com.intellij.mcpserver.util.Fs_utilKt;
import com.intellij.openapi.application.ex.ApplicationManagerEx;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.NewVirtualFile;
import dev.ideamcp.protocol.Arguments;
import dev.ideamcp.protocol.QueryTiming;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static dev.ideamcp.protocol.Json.object;

/** Fresh disk enumeration. Never returns a partial tree; null delegates to the official provider. */
final class DirectoryTree {
    private static final long BUDGET = TimeUnit.MILLISECONDS.toNanos(200);
    private static final int MIN_ROOT_ENTRIES = 32;
    private static final int PROBE_LIMIT = 512;
    private record Entry(Path path, boolean directory, String key) {}

    private static final class Count {
        int files, directories, entries;
    }

    /** Fresh output traversal, independent of the preceding selection-only name probe. */
    private static final class Traversal {
        final Map<Path, BasicFileAttributes> stamps = new LinkedHashMap<>();
        final long deadline;
        Traversal(long deadline) { this.deadline = deadline; }

        List<Entry> list(Path directory) throws Exception {
            check(deadline);
            if (!stamps.containsKey(directory)) {
                BasicFileAttributes before = attributes(directory);
                if (!before.isDirectory() || before.isSymbolicLink() || before.isOther()
                        || !directory.toRealPath().equals(directory.toAbsolutePath().normalize())) throw new UnsupportedTree();
                stamps.put(directory, before);
            }
            List<Entry> entries = new ArrayList<>();
            Set<String> keys = new HashSet<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                for (Path child : stream) {
                    check(deadline);
                    String name = child.getFileName().toString();
                    BasicFileAttributes attributes = attributes(child);
                    if (attributes.isSymbolicLink() || attributes.isOther()
                            || (!attributes.isDirectory() && !attributes.isRegularFile())) throw new UnsupportedTree();
                    Entry entry = new Entry(child, attributes.isDirectory(), name.toLowerCase(Locale.ROOT));
                    if (!keys.add(entry.key())) throw new UnsupportedTree();
                    entries.add(entry);
                }
            }
            return entries;
        }

        boolean qualify(Path directory, int depth) throws Exception {
            // A shape hint only: fresh full attributes and snapshots are read during rendering.
            List<Path> names = names(directory, MIN_ROOT_ENTRIES);
            if (names.size() < MIN_ROOT_ENTRIES) return false;
            BasicFileAttributes first = attributes(names.getFirst());
            if (first.isRegularFile()) return true;
            if (!first.isDirectory() || first.isSymbolicLink() || first.isOther()) return false;
            List<BasicFileAttributes> types = new ArrayList<>();
            types.add(first);
            for (int i = 1; i < Math.min(4, names.size()); i++) {
                BasicFileAttributes child = attributes(names.get(i));
                if (child.isRegularFile()) return true;
                if (!child.isDirectory() || child.isSymbolicLink() || child.isOther()) return false;
                types.add(child);
            }
            if (depth <= 2) return false;
            int leaves = 0;
            for (int i = 0; i < Math.min(4, names.size()); i++) {
                check(deadline);
                BasicFileAttributes child = types.get(i);
                if (!child.isDirectory() || child.isSymbolicLink() || child.isOther()) return false;
                int remaining = PROBE_LIMIT - names.size() - leaves;
                if (remaining <= 0) return false;
                leaves += names(names.get(i), remaining).size();
                if (leaves >= 256) return true;
            }
            return false;
        }

        List<Path> names(Path directory, int limit) throws Exception {
            List<Path> names = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                for (Path child : stream) {
                    check(deadline);
                    names.add(child);
                    if (names.size() >= limit) break;
                }
            }
            return names;
        }
    }

    static JsonObject tryCall(Project project, JsonObject args, long requestDeadline) throws Exception {
        ProgressManager.checkCanceled();
        if (System.nanoTime() >= requestDeadline) return null;
        String input = args.get("directoryPath").getAsString();
        int depth = Arguments.integer(args, "maxDepth", 5, Integer.MIN_VALUE, Integer.MAX_VALUE);
        int timeout = Arguments.integer(args, "timeout", 10000, Integer.MIN_VALUE, Integer.MAX_VALUE);
        if (depth < 2 || depth > 100 || timeout < 1000 || project.isDisposed()) return null;
        // Leave non-local/parent-path resolution and its errors with the provider.
        Path relative;
        try { relative = Path.of(input); }
        catch (InvalidPathException invalid) { return null; }
        if (relative.isAbsolute()) return null;
        for (Path part : relative) if (part.toString().equals("..")) return null;
        long deadline = Math.min(requestDeadline, System.nanoTime() + Math.min(BUDGET, TimeUnit.MILLISECONDS.toNanos(timeout)));
        try {
            Path root = Fs_utilKt.resolveInProject(project, input, false);
            Traversal traversal = new Traversal(deadline);
            try (var phase = QueryTiming.phase("tree_qualification")) {
                boolean[] small = {false};
                try (var cachedPhase = QueryTiming.phase("tree_cached_qualification")) {
                    ApplicationManagerEx.getApplicationEx().tryRunReadAction(() -> {
                        var cached = LocalFileSystem.getInstance().findFileByPathIfCached(root.toString().replace('\\', '/'));
                        if (cached instanceof NewVirtualFile directory && directory.allChildrenCached()) {
                            var children = directory.getCachedChildren();
                            Count count = new Count();
                            small[0] = children.size() < MIN_ROOT_ENTRIES
                                    || (children.stream().limit(MIN_ROOT_ENTRIES).filter(VirtualFile::isDirectory).count() >= 24
                                    && cachedComplete(directory, depth, count) && count.files < count.directories * 4);
                        }
                    });
                }
                // Stale negative information only delegates to a fresh official query.
                if (small[0]) return null;
                try (var diskPhase = QueryTiming.phase("tree_disk_qualification")) {
                    if (!traversal.qualify(root, depth)) return null;
                }
            }
            try (var phase = QueryTiming.phase("tree_render")) {
                for (Path parent = root; parent != null; parent = parent.getParent()) {
                    check(deadline);
                    BasicFileAttributes attributes = attributes(parent);
                    if (attributes.isSymbolicLink() || attributes.isOther()) return null;
                    if (!parent.toRealPath().equals(parent.toAbsolutePath().normalize())) return null;
                }
                BasicFileAttributes attributes = attributes(root);
                if (!attributes.isDirectory()) return null;
                StringBuilder tree = new StringBuilder();
                if (!render(new Entry(root, true, ""), "", true, depth, true, tree, traversal)) return null;
                for (var snapshot : traversal.stamps.entrySet()) {
                    check(deadline);
                    BasicFileAttributes now = attributes(snapshot.getKey());
                    if (!now.isDirectory() || now.isSymbolicLink() || now.isOther()
                            || !Objects.equals(now.fileKey(), snapshot.getValue().fileKey())
                            || !now.lastModifiedTime().equals(snapshot.getValue().lastModifiedTime())) return null;
                }
                check(deadline);
                JsonObject data = object("traversedDirectory", input, "tree", tree.toString(), "errors", new String[0]);
                return object("content", new Object[]{object("type", "text", "text", data.toString())}, "structuredContent", data, "isError", false);
            }
        } catch (BudgetExceeded | UnsupportedTree | IOException | DirectoryIteratorException | SecurityException | com.intellij.mcpserver.McpExpectedError unsupported) {
            return null;
        }
    }

    private static boolean cachedComplete(VirtualFile file, int depth, Count count) {
        ProgressManager.checkCanceled();
        if (++count.entries > PROBE_LIMIT) return false;
        if (!file.isDirectory()) { count.files++; return true; }
        count.directories++;
        if (depth <= 1) return true;
        if (!(file instanceof NewVirtualFile directory) || !directory.allChildrenCached()) return false;
        for (var child : directory.getCachedChildren()) if (!cachedComplete(child, depth - 1, count)) return false;
        return true;
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean render(Entry entry, String prefix, boolean last, int depth, boolean root,
                                  StringBuilder tree, Traversal traversal) throws Exception {
        check(traversal.deadline);
        tree.append(prefix).append(prefix.isEmpty() ? "" : last ? "└── " : "├── ")
                .append(root ? entry.path().toFile().getAbsolutePath() : entry.path().getFileName().toString())
                .append(entry.directory() ? "/\n" : "\n");
        if (!entry.directory()) return true;
        if (depth == 1) {
            // Descendants are outside the requested result. Check access without
            // loading their attributes or sorting names that will not be rendered.
            BasicFileAttributes before = attributes(entry.path());
            if (!before.isDirectory() || before.isSymbolicLink() || before.isOther()
                    || !entry.path().toRealPath().equals(entry.path().toAbsolutePath().normalize())) return false;
            traversal.stamps.putIfAbsent(entry.path(), before);
            try (DirectoryStream<Path> ignored = Files.newDirectoryStream(entry.path())) { }
            return true;
        }
        List<Entry> children = traversal.list(entry.path());
        children.sort(Comparator.comparing(Entry::key));
        if (depth > 1) {
            String childPrefix = prefix + (last ? "    " : "│   ");
            for (int i = 0; i < children.size(); i++)
                if (!render(children.get(i), childPrefix, i == children.size() - 1, depth - 1, false, tree, traversal)) return false;
        }
        return true;
    }

    private static void check(long deadline) throws InterruptedException {
        ProgressManager.checkCanceled();
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Directory traversal interrupted");
        if (System.nanoTime() >= deadline) throw new BudgetExceeded();
    }
    private static final class BudgetExceeded extends RuntimeException {}
    private static final class UnsupportedTree extends RuntimeException {}
}
