package dev.ideamcp;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import dev.ideamcp.protocol.Arguments;
import dev.ideamcp.protocol.RpcHandler;
import java.util.Set;
import java.util.concurrent.*;

/** Routes code reads to background read actions and execution operations to the IDE UI thread. */
final class IdeaToolBackend implements RpcHandler.Backend {
    private static final Set<String> RUN_TOOLS = Set.of("list_run_configurations", "restart_run_configuration", "get_run_operation", "read_run_logs");
    private final JavaCodeTools code = new JavaCodeTools();
    @Override public JsonObject call(String name, JsonObject arguments) throws Exception {
        if (DebugService.NAMES.contains(name)) return DebugService.invoke(name, arguments);
        if (!RUN_TOOLS.contains(name)) return code.call(name, arguments);
        var app = ApplicationManager.getApplication();
        Callable<JsonObject> action = () -> {
            var project = JavaCodeTools.selectProject(Arguments.string(arguments, "project", null));
            if (project.isDisposed()) throw new IllegalArgumentException("PROJECT_CLOSED");
            if (name.equals("read_run_logs")) return project.getService(RunLogService.class).read(arguments);
            return project.getService(RunControlService.class).call(name, arguments);
        };
        if (app.isDispatchThread()) return action.call();
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        app.invokeLater(() -> {
            if (result.isCancelled()) return;
            try { result.complete(action.call()); }
            catch (Exception e) { result.completeExceptionally(e); }
        });
        try { return result.get(5, TimeUnit.SECONDS); }
        catch (TimeoutException e) { result.cancel(false); throw new IllegalArgumentException("IDE_BUSY: IDEA did not handle the request within 5 seconds; inspect current run operations before retrying a restart"); }
        catch (InterruptedException e) { result.cancel(false); Thread.currentThread().interrupt(); throw e; }
        catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }
}
