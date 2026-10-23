package dev.ideamcp;

import com.google.gson.*;
import com.intellij.codeInsight.CodeSmellInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.util.ProgressIndicatorBase;
import com.intellij.openapi.project.*;
import com.intellij.openapi.vcs.CodeSmellDetector;
import com.intellij.psi.*;
import com.intellij.util.concurrency.AppExecutorUtil;
import dev.ideamcp.protocol.Arguments;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import com.intellij.openapi.util.Key;
import static dev.ideamcp.protocol.Json.object;

/** On-demand IDEA error/warning analysis, outside a long-lived read lock. No compilation or fixes. */
final class DiagnosticsTools {
    private static final Key<ReentrantLock> LOCK = Key.create("dev.ideamcp.diagnostics.lock");
    private record Snapshot(Project project, PsiJavaFile file, Document document, long stamp, long psiStamp) {}
    static JsonObject call(JsonObject args) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        Snapshot before = JavaCodeTools.read(null, () -> {
            Project project = JavaCodeTools.selectProject(Arguments.string(args, "project", null));
            ready(project);
            PsiJavaFile file = JavaCodeTools.file(project, Arguments.required(args, "file"));
            Document document = PsiDocumentManager.getInstance(project).getDocument(file);
            if (document == null) throw new IllegalArgumentException("NO_DOCUMENT");
            return new Snapshot(project, file, document, document.getModificationStamp(), PsiManager.getInstance(project).getModificationTracker().getModificationCount());
        }, deadline - System.nanoTime());
        ReentrantLock lock;
        synchronized (before.project) {
            lock = before.project.getUserData(LOCK);
            if (lock == null) { lock = new ReentrantLock(); before.project.putUserData(LOCK, lock); }
        }
        ProgressIndicatorBase indicator = new ProgressIndicatorBase();
        // The worker owns the lock until cancellation has actually unwound IDEA's analysis.
        ReentrantLock selectedLock = lock;
        Future<List<CodeSmellInfo>> future = AppExecutorUtil.getAppExecutorService().submit(() -> {
            if (!selectedLock.tryLock()) throw new IllegalArgumentException("DIAGNOSTICS_BUSY: retry later");
            try { return ProgressManager.getInstance().runProcess(() -> CodeSmellDetector.getInstance(before.project).findCodeSmells(List.of(before.file.getVirtualFile())), indicator); }
            finally { selectedLock.unlock(); }
        });
        try {
            List<CodeSmellInfo> found = future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            return JavaCodeTools.read(before.project, () -> {
                ready(before.project);
                if (!before.file.isValid() || before.document.getModificationStamp() != before.stamp
                        || PsiManager.getInstance(before.project).getModificationTracker().getModificationCount() != before.psiStamp)
                    throw new IllegalArgumentException("SOURCE_CHANGED: source changed during analysis; retry from offset 0");
                String severity = Arguments.string(args, "severity", "all");
                if (!Set.of("all", "error", "warning").contains(severity)) throw new IllegalArgumentException("severity must be all, error or warning");
                List<CodeSmellInfo> filtered = found.stream().filter(info -> severity.equals("all") || severity.equals(level(info)))
                        .sorted(Comparator.comparingInt((CodeSmellInfo i) -> i.getTextRange().getStartOffset()).thenComparingInt(i -> i.getTextRange().getEndOffset()).thenComparing(CodeSmellInfo::getDescription)).toList();
                int offset = Arguments.integer(args, "offset", 0, 0, 1000000), limit = Arguments.integer(args, "limit", 100, 1, 200);
                JsonArray items = new JsonArray();
                filtered.stream().skip(offset).limit(limit).forEach(info -> items.add(object("severity", level(info), "message", info.getDescription(), "location", JavaCodeTools.location(before.file, info.getTextRange()))));
                boolean more = offset + items.size() < filtered.size();
                return object("file", before.file.getVirtualFile().getPath(), "documentStamp", Long.toString(before.stamp), "diagnostics", items, "total", filtered.size(),
                        "offset", offset, "limit", limit, "hasMore", more, "nextOffset", more ? offset + items.size() : null,
                        "analysis", "on-demand IDEA code-smell error/warning analysis using its inspection profile; no compiler, build, quick fixes or external build diagnostics; restart paging after source/profile/index changes");
            }, deadline - System.nanoTime());
        } catch (TimeoutException e) { throw new IllegalArgumentException("TIMEOUT: diagnostics exceeded 20 seconds; retry with a smaller file"); }
        catch (ExecutionException e) { if (e.getCause() instanceof Exception cause) throw cause; throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
        finally { indicator.cancel(); }
    }
    private static String level(CodeSmellInfo info) { return info.getSeverity().compareTo(HighlightSeverity.ERROR) >= 0 ? "error" : "warning"; }
    private static void ready(Project project) {
        if (project.isDisposed()) throw new IllegalArgumentException("PROJECT_CLOSED");
        if (DumbService.isDumb(project)) throw new IllegalArgumentException("INDEXING: retry after indexing");
        if (PsiDocumentManager.getInstance(project).hasUncommitedDocuments()) throw new IllegalArgumentException("DOCUMENTS_UNCOMMITTED: retry after editor changes settle");
    }
}
