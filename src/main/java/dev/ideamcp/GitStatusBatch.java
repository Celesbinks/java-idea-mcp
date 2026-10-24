package dev.ideamcp;

import com.google.gson.*;
import com.intellij.mcpserver.util.Fs_utilKt;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.*;
import com.intellij.util.concurrency.AppExecutorUtil;
import git4idea.repo.*;
import dev.ideamcp.protocol.Arguments;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static dev.ideamcp.protocol.Json.object;

/** Parallel scheduling only; every status and its serialization still comes from the SDK. */
final class GitStatusBatch {
    private record Root(GitRepository repository, VirtualFile file, String path, String branch) {}

    static JsonObject tryCall(Project project, JsonObject args, long deadline) throws Exception {
        check(deadline);
        if (args.has("repositoryPathRelativeToProject")) return null;
        if (Arguments.bool(args, "includeIgnored", false) && !Arguments.bool(args, "includeUntracked", true)) return null;
        List<Root> roots = roots(project, deadline);
        if (roots == null) return null;
        List<Future<JsonObject>> futures = new ArrayList<>();
        try {
            for (Root root : roots) {
                check(deadline);
                JsonObject child = args.deepCopy(); child.addProperty("repositoryPathRelativeToProject", root.path);
                futures.add(ApplicationManager.getApplication().getService(Workers.class).executor.submit(() -> {
                    check(deadline);
                    return new OfficialMcpAdapter(false, false).callOfficialGit(child);
                }));
            }
            JsonArray repositories = new JsonArray();
            for (int i = 0; i < futures.size(); i++) {
                check(deadline);
                JsonObject result;
                try { result = futures.get(i).get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                catch (ExecutionException failure) { return null; } // Preserve the original provider error, not a partial batch.
                if (result.get("isError").getAsBoolean() || !result.has("structuredContent")) return null;
                JsonArray values = result.getAsJsonObject("structuredContent").getAsJsonArray("repositories");
                if (values.size() != 1 || !values.get(0).getAsJsonObject().get("repositoryPathRelativeToProject").getAsString().equals(roots.get(i).path)) return null;
                JsonElement branch = values.get(0).getAsJsonObject().get("currentBranch");
                if (!Objects.equals(roots.get(i).branch, branch == null || branch.isJsonNull() ? null : branch.getAsString())) return null;
                repositories.add(values.get(0));
            }
            check(deadline);
            if (!roots.equals(roots(project, deadline))) return null;
            JsonObject data = object("repositories", repositories);
            return object("content", new Object[]{object("type", "text", "text", data.toString())}, "isError", false, "structuredContent", data);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            for (Future<?> future : futures) if (!future.isDone()) future.cancel(true);
        }
    }

    private static List<Root> roots(Project project, long deadline) throws Exception {
        // The SDK reads this thread-safe manager in the background too. Avoid scheduling
        // a read action for the common single-root case; validate the list again inside it.
        int count = GitRepositoryManager.getInstance(project).getRepositories().size();
        if (count < 2 || count > 4) return null;
        return JavaCodeTools.read(project, () -> {
            check(deadline);
            Path base = Fs_utilKt.getProjectDirectory(project);
            var repositories = GitRepositoryManager.getInstance(project).getRepositories();
            if (repositories.size() < 2 || repositories.size() > 4) return null;
            List<Root> roots = new ArrayList<>();
            for (GitRepository repository : repositories) {
                VirtualFile file = repository.getRoot(); Path path = file.toNioPath();
                if (!path.startsWith(base)) return null;
                for (VirtualFile parent = file; parent != null; parent = parent.getParent())
                    if (parent.is(VFileProperty.SYMLINK)) return null;
                for (Root other : roots)
                    if (path.startsWith(other.file.toNioPath()) || other.file.toNioPath().startsWith(path)) return null;
                roots.add(new Root(repository, file, Fs_utilKt.relativizeIfPossible(base, file), repository.getCurrentBranchName()));
            }
            roots.sort(Comparator.comparing(Root::path));
            return List.copyOf(roots);
        }, Math.max(1, deadline - System.nanoTime()));
    }

    private static void check(long deadline) {
        ProgressManager.checkCanceled();
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Git status query cancelled");
        if (System.nanoTime() >= deadline) throw new CancellationException("Git status query timed out");
    }

    @Service(Service.Level.APP)
    public static final class Workers implements Disposable {
        final ExecutorService executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Java MCP Git status", 4);
        @Override public void dispose() { executor.shutdownNow(); }
    }
}
