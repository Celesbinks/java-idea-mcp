package dev.ideamcp;

import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.util.concurrency.AppExecutorUtil;
import dev.ideamcp.protocol.Arguments;

import java.util.*;
import java.util.concurrent.*;

import static dev.ideamcp.protocol.Json.object;

/**
 * Long official operations never hold an HTTP worker for a build or a process lifetime.
 */
@Service(Service.Level.APP)
public final class PlatformOperationService implements Disposable {
    static final Set<String> LONG_TOOLS = Set.of("execute_tool", "build_project", "execute_run_configuration", "execute_terminal_command", "run_inspection_kts", "execute_sql_query", "test_database_connection", "preview_table_data", "introspect_schema", "xdebug_start_debugger_session", "xdebug_control_session", "xdebug_run_to_line", "xdebug_evaluate_expression", "xdebug_set_variable");
    private final Map<String, Operation> operations = new LinkedHashMap<>();
    private final ExecutorService worker = AppExecutorUtil.createBoundedApplicationPoolExecutor("idea-mcp-platform", 4);
    private boolean disposed;

    synchronized JsonObject submit(String tool, Callable<JsonObject> action) {
        if (disposed) throw new IllegalStateException("PLUGIN_DISPOSED");
        while (operations.size() >= 100) {
            var oldest = operations.entrySet().stream().filter(e -> !e.getValue().snapshot().get("inProgress").getAsBoolean()).findFirst().orElseThrow(() -> new IllegalStateException("TOO_MANY_OPERATIONS"));
            operations.remove(oldest.getKey());
        }
        Operation operation = new Operation(tool);
        operations.put(operation.id, operation);
        operation.future = worker.submit(() -> {
            synchronized (operation) {
                if (operation.cancelRequested) return;
                operation.phase = "running";
            }
            try {
                JsonObject result = action.call();
                synchronized (operation) {
                    operation.result = result;
                    operation.phase = result.has("isError") && result.get("isError").getAsBoolean() ? "failed" : "completed";
                }
            } catch (Exception e) {
                synchronized (operation) {
                    operation.error = e instanceof CancellationException || e instanceof InterruptedException ? "Operation cancelled; side effects may already have occurred" : "Platform tool failed: " + e.getClass().getSimpleName();
                    operation.phase = operation.cancelRequested ? "cancelled" : "failed";
                }
            }
        });
        return operation.snapshot();
    }

    synchronized JsonObject query(JsonObject args, boolean cancel) {
        Operation o = operations.get(Arguments.required(args, "operationId"));
        if (o == null) throw new IllegalArgumentException("TOOL_OPERATION_NOT_FOUND");
        if (cancel) {
            synchronized (o) {
                if (o.phase.equals("queued") || o.phase.equals("running")) {
                    boolean queued = o.phase.equals("queued");
                    o.cancelRequested = true;
                    o.phase = queued ? "cancelled" : "cancel_requested";
                    if (o.future != null) o.future.cancel(true);
                    o.error = "Cancellation requested; this does not undo side effects or stop business processes";
                }
            }
        }
        return o.snapshot();
    }

    @Override
    public synchronized void dispose() {
        disposed = true;
        for (Operation o : operations.values()) query(object("operationId", o.id), true);
        worker.shutdownNow();
        operations.clear();
    }

    private static final class Operation {
        final String id = UUID.randomUUID().toString(), tool;
        String phase = "queued", error;
        boolean cancelRequested;
        JsonObject result;
        Future<?> future;

        Operation(String tool) {
            this.tool = tool;
        }

        synchronized JsonObject snapshot() {
            return object("accepted", true, "operationId", id, "tool", tool, "phase", phase, "inProgress", Set.of("queued", "running", "cancel_requested").contains(phase), "cancelRequested", cancelRequested, "error", error, "result", result == null ? null : result.deepCopy());
        }
    }
}
