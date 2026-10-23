package dev.ideamcp;

import com.google.gson.*;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.*;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.*;
import com.intellij.psi.search.*;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.util.concurrency.AppExecutorUtil;
import dev.ideamcp.protocol.Arguments;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static dev.ideamcp.protocol.Json.object;

public final class JavaCodeTools {
    public JsonObject call(String name, JsonObject args) throws Exception {
        if (name.equals("list_projects")) return read(null, this::projects);
        if (name.equals("get_diagnostics")) return DiagnosticsTools.call(args);
        return read(null, () -> {
            Project project = selectProject(Arguments.string(args, "project", null));
            if (project.isDisposed()) throw new IllegalArgumentException("PROJECT_CLOSED: project has closed");
            if (DumbService.isDumb(project)) throw new IllegalArgumentException("INDEXING: wait for IDEA indexing to finish, then retry");
            if (PsiDocumentManager.getInstance(project).hasUncommitedDocuments())
                throw new IllegalArgumentException("DOCUMENTS_UNCOMMITTED: IDEA is committing editor changes; retry shortly");
            return switch (name) {
                case "read_symbols" -> symbols(project, args);
                case "find_definition" -> definitions(project, args);
                case "find_references" -> references(project, args);
                case "find_callers" -> JavaCallTools.calls(project, args, true);
                case "find_callees" -> JavaCallTools.calls(project, args, false);
                default -> throw new IllegalArgumentException("Unknown tool: " + name);
            };
        });
    }

    static <T> T read(Project project, Supplier<T> action) throws Exception {
        return read(project, action, TimeUnit.SECONDS.toNanos(20));
    }
    static <T> T read(Project project, Supplier<T> action, long timeoutNanos) throws Exception {
        // Expected query failures must not become rejected IDE promises: those are logged as
        // platform errors (and fail the IDE test harness). Transport reports these as tool errors.
        var read = ReadAction.nonBlocking(() -> {
            try { return new ReadResult<T>(action.get(), null); }
            catch (IllegalArgumentException e) { return new ReadResult<T>(null, e); }
        });
        if (project != null) read = read.expireWith(project);
        var promise = read.submit(AppExecutorUtil.getAppExecutorService());
        try {
            ReadResult<T> result = promise.get(Math.max(1, timeoutNanos), TimeUnit.NANOSECONDS);
            if (result.error() != null) throw result.error();
            return result.value();
        }
        catch (TimeoutException e) { promise.cancel(); throw new IllegalArgumentException("TIMEOUT: query exceeded 20 seconds; narrow the query or retry"); }
        catch (InterruptedException e) { promise.cancel(); Thread.currentThread().interrupt(); throw e; }
        catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }
    private record ReadResult<T>(T value, IllegalArgumentException error) {}
    private JsonObject projects() {
        JsonArray result = new JsonArray();
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (!project.isDisposed()) result.add(object("name", project.getName(), "path", project.getBasePath(), "indexing", DumbService.isDumb(project)));
        }
        return object("projects", result);
    }
    static Project selectProject(String selector) {
        List<Project> projects = Arrays.stream(ProjectManager.getInstance().getOpenProjects()).filter(p -> !p.isDisposed() && p.getBasePath() != null).toList();
        if (selector == null) {
            if (projects.size() != 1) throw new IllegalArgumentException("PROJECT_REQUIRED: use list_projects and pass an exact project path (open projects: " + projects.size() + ")");
            return projects.getFirst();
        }
        Path target = Path.of(selector).toAbsolutePath().normalize();
        return projects.stream().filter(p -> Path.of(p.getBasePath()).toAbsolutePath().normalize().equals(target)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("PROJECT_NOT_FOUND: use an exact path from list_projects"));
    }
    static PsiJavaFile file(Project project, String path) {
        Path target = Path.of(path);
        if (!target.isAbsolute()) target = Path.of(Objects.requireNonNull(project.getBasePath())).resolve(target);
        VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByNioFile(target.normalize());
        if (virtualFile == null || virtualFile.isDirectory()) throw new IllegalArgumentException("FILE_NOT_FOUND: file is not in IDEA's VFS");
        if (!ProjectFileIndex.getInstance(project).isInContent(virtualFile)) throw new IllegalArgumentException("OUTSIDE_PROJECT: file must be inside project content");
        PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
        if (!(psiFile instanceof PsiJavaFile javaFile)) throw new IllegalArgumentException("NOT_JAVA: expected a Java source file");
        return javaFile;
    }
    private JsonObject symbols(Project project, JsonObject args) {
        Page page = new Page(args);
        String path = Arguments.string(args, "file", null);
        String query = Arguments.string(args, "query", null);
        if ((path == null) == (query == null)) throw new IllegalArgumentException("Supply exactly one of file/query");
        if (path != null) {
            PsiJavaFile file = file(project, path);
            try {
                file.accept(new JavaRecursiveElementWalkingVisitor() {
                    @Override public void visitElement(PsiElement element) {
                        ProgressManager.checkCanceled();
                        if (element instanceof PsiClass || element instanceof PsiMethod || element instanceof PsiField) {
                            if (!page.add(symbol(element))) { stopWalking(); return; }
                        }
                        super.visitElement(element);
                    }
                });
            } catch (com.intellij.openapi.progress.ProcessCanceledException e) { throw e; }
            return page.result("symbols");
        }
        GlobalSearchScope scope = Arguments.bool(args, "includeLibraries", false) ? GlobalSearchScope.allScope(project) : GlobalSearchScope.projectScope(project);
        PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
        Set<String> seen = new HashSet<>();
        List<PsiElement> matches = new ArrayList<>();
        if (query.contains(".")) Collections.addAll(matches, JavaPsiFacade.getInstance(project).findClasses(query, scope));
        else {
            Collections.addAll(matches, cache.getClassesByName(query, scope));
            cache.processMethodsWithName(query, scope, method -> { ProgressManager.checkCanceled(); matches.add(method); return matches.size() <= 10000; });
            Collections.addAll(matches, cache.getFieldsByName(query, scope));
        }
        matches.sort(Comparator.comparing(JavaCodeTools::sortKey));
        for (PsiElement element : matches) {
            ProgressManager.checkCanceled();
            if (!element.getLanguage().isKindOf(com.intellij.lang.java.JavaLanguage.INSTANCE)) continue;
            if (seen.add(sortKey(element)) && !page.add(symbol(element))) break;
        }
        JsonObject result = page.result("symbols");
        result.addProperty("candidateLimitReached", matches.size() > 10000);
        return result;
    }
    static List<PsiElement> targets(Project project, JsonObject args) {
        PsiJavaFile file = file(project, Arguments.required(args, "file"));
        Document document = PsiDocumentManager.getInstance(project).getDocument(file);
        if (document == null) throw new IllegalArgumentException("NO_DOCUMENT: file has no source document");
        int line = Arguments.integer(args, "line", 0, 1, Integer.MAX_VALUE) - 1;
        int column = Arguments.integer(args, "column", 0, 1, Integer.MAX_VALUE) - 1;
        if (line < 0 || line >= document.getLineCount()) throw new IllegalArgumentException("line is outside file");
        int start = document.getLineStartOffset(line);
        if (column < 0 || column >= document.getLineEndOffset(line) - start) throw new IllegalArgumentException("column must point at a character within the line");
        int offset = start + column;
        PsiReference reference = file.findReferenceAt(offset);
        List<PsiElement> result = new ArrayList<>();
        if (reference instanceof PsiPolyVariantReference poly) {
            for (ResolveResult resolution : poly.multiResolve(false)) if (resolution.isValidResult() && resolution.getElement() != null) result.add(resolution.getElement());
        } else if (reference != null) {
            PsiElement resolved = reference.resolve();
            if (resolved != null) result.add(resolved);
        }
        // Only treat a name identifier as a declaration. Whitespace in a method body is never its enclosing method.
        if (reference == null) {
            PsiElement leaf = file.findElementAt(offset);
            if (leaf != null && leaf.getParent() instanceof PsiNameIdentifierOwner owner && owner.getNameIdentifier() != null
                    && owner.getNameIdentifier().getTextRange().contains(offset)) result.add(owner);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("UNRESOLVED: no resolvable Java symbol at this position");
        LinkedHashMap<String, PsiElement> unique = new LinkedHashMap<>();
        result.forEach(e -> unique.putIfAbsent(sortKey(e), e));
        return new ArrayList<>(unique.values());
    }
    private JsonObject definitions(Project project, JsonObject args) {
        JsonArray definitions = new JsonArray();
        for (PsiElement element : targets(project, args)) definitions.add(symbol(element));
        return object("definitions", definitions);
    }
    private JsonObject references(Project project, JsonObject args) {
        List<PsiElement> targets = targets(project, args);
        if (targets.size() != 1) throw new IllegalArgumentException("AMBIGUOUS: position resolves to multiple symbols");
        PsiElement target = targets.getFirst();
        Page page = new Page(args);
        // Search callbacks may arrive in a different order on each request. Applying
        // offset/limit while traversing the query can therefore repeat or skip usages.
        // Deduplicate and order the complete result before taking any page.
        NavigableMap<ReferenceKey, ReferenceLocation> references = new TreeMap<>(Comparator
                .comparing(ReferenceKey::fileUrl).thenComparingInt(ReferenceKey::start).thenComparingInt(ReferenceKey::end));
        ReferencesSearch.search(target, GlobalSearchScope.projectScope(project)).forEach(reference -> {
            ProgressManager.checkCanceled();
            PsiElement element = reference.getElement();
            PsiFile file = element.getContainingFile();
            if (file == null || file.getVirtualFile() == null) return true;
            TextRange range = reference.getRangeInElement().shiftRight(element.getTextRange().getStartOffset());
            ReferenceKey key = new ReferenceKey(file.getVirtualFile().getUrl(), range.getStartOffset(), range.getEndOffset());
            references.putIfAbsent(key, new ReferenceLocation(element, range));
            return true;
        });
        boolean includeDeclaration = Arguments.bool(args, "includeDeclaration", false);
        if (includeDeclaration) page.addLazy(() -> object("location", location(target, nameRange(target)), "declaration", true));
        for (ReferenceLocation reference : references.values()) {
            ProgressManager.checkCanceled();
            if (!page.addLazy(() -> object("location", location(reference.element(), reference.range()), "declaration", false))) break;
        }
        JsonObject result = page.result("references");
        result.addProperty("total", references.size() + (includeDeclaration ? 1 : 0));
        result.add("target", symbol(target));
        result.addProperty("order", "declaration first when requested, then file URL, start offset and end offset; stable while project contents and indices are unchanged");
        return result;
    }
    private record ReferenceKey(String fileUrl, int start, int end) {}
    private record ReferenceLocation(PsiElement element, TextRange range) {}
    private static String sortKey(PsiElement element) {
        PsiFile file = element.getContainingFile();
        VirtualFile vf = file == null ? null : file.getVirtualFile();
        return (vf == null ? "" : vf.getUrl()) + ":" + element.getTextOffset() + ":" + element;
    }
    static JsonObject symbol(PsiElement element) {
        String kind = "symbol", qualified = null, signature = element.toString();
        String name = element instanceof PsiNamedElement named ? named.getName() : element.getText();
        if (element instanceof PsiClass cls) {
            kind = cls.isAnnotationType() ? "annotation" : cls.isEnum() ? "enum" : cls.isInterface() ? "interface" : cls.isRecord() ? "record" : "class";
            qualified = cls.getQualifiedName(); signature = kind + " " + (qualified == null ? name : qualified);
        } else if (element instanceof PsiMethod method) {
            kind = method.isConstructor() ? "constructor" : "method";
            PsiClass owner = method.getContainingClass();
            qualified = (owner == null ? "" : owner.getQualifiedName() + "#") + method.getName();
            String parameters = String.join(", ", Arrays.stream(method.getParameterList().getParameters()).map(p -> p.getType().getCanonicalText() + " " + p.getName()).toList());
            signature = (method.getReturnType() == null ? "" : method.getReturnType().getCanonicalText() + " ") + method.getName() + "(" + parameters + ")";
        } else if (element instanceof PsiVariable variable) {
            kind = element instanceof PsiEnumConstant ? "enum_constant" : element instanceof PsiField ? "field" : element instanceof PsiParameter ? "parameter" : "local_variable";
            signature = variable.getType().getCanonicalText() + " " + name;
            if (element instanceof PsiField field && field.getContainingClass() != null) qualified = field.getContainingClass().getQualifiedName() + "#" + name;
        } else if (element instanceof PsiPackage pkg) { kind = "package"; qualified = pkg.getQualifiedName(); signature = "package " + qualified; }
        JsonArray modifiers = new JsonArray();
        if (element instanceof PsiModifierListOwner owner) {
            for (String modifier : new String[]{"public", "protected", "private", "static", "final", "abstract", "synchronized", "native", "volatile", "transient", "default", "sealed", "non-sealed"})
                if (owner.hasModifierProperty(modifier)) modifiers.add(modifier);
        }
        PsiElement navigation = element.getNavigationElement();
        return object("name", name, "kind", kind, "qualifiedName", qualified, "signature", signature, "modifiers", modifiers,
                "location", location(element, nameRange(element)), "navigationLocation", location(navigation, nameRange(navigation)),
                "compiled", element instanceof PsiCompiledElement);
    }
    private static TextRange nameRange(PsiElement element) {
        if (element instanceof PsiNameIdentifierOwner owner && owner.getNameIdentifier() != null) return owner.getNameIdentifier().getTextRange();
        return element.getTextRange();
    }
    static JsonObject location(PsiElement element, TextRange range) {
        PsiFile file = element.getContainingFile();
        if (file == null || file.getVirtualFile() == null || range == null) return null;
        Document document = PsiDocumentManager.getInstance(element.getProject()).getDocument(file);
        VirtualFile vf = file.getVirtualFile();
        JsonObject result = object("path", vf.getPath(), "url", vf.getUrl());
        if (document == null) return result;
        int start = Math.min(range.getStartOffset(), document.getTextLength());
        int end = Math.min(range.getEndOffset(), document.getTextLength());
        int line = document.getLineNumber(start), endLine = document.getLineNumber(end);
        result.addProperty("line", line + 1); result.addProperty("column", start - document.getLineStartOffset(line) + 1);
        result.addProperty("endLine", endLine + 1); result.addProperty("endColumn", end - document.getLineStartOffset(endLine) + 1);
        result.addProperty("snippet", document.getText(new TextRange(document.getLineStartOffset(line), Math.min(document.getLineEndOffset(line), document.getLineStartOffset(line) + 500))));
        return result;
    }
    private static final class Page {
        private final int offset, limit;
        private int visited;
        private boolean more;
        private final JsonArray items = new JsonArray();
        Page(JsonObject args) {
            offset = Arguments.integer(args, "offset", 0, 0, 1000000);
            limit = Arguments.integer(args, "limit", 100, 1, 200);
        }
        boolean add(JsonObject item) {
            return addLazy(() -> item);
        }
        boolean addLazy(Supplier<JsonObject> item) {
            if (visited++ < offset) return true;
            if (items.size() == limit) { more = true; return false; }
            items.add(item.get()); return true;
        }
        JsonObject result(String key) { return object(key, items, "offset", offset, "limit", limit, "hasMore", more, "nextOffset", more ? offset + items.size() : null); }
    }
}
