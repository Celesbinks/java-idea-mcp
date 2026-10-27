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
    static final Set<String> LONG_TOOLS = Set.of("create_new_file", "apply_patch", "reformat_file", "rename_refactoring", "execute_tool", "build_project", "execute_run_configuration", "execute_terminal_command", "run_inspection_kts", "execute_sql_query", "test_database_connection", "preview_table_data", "introspect_schema", "xdebug_start_debugger_session", "xdebug_control_session", "xdebug_run_to_line", "xdebug_evaluate_expression", "xdebug_set_variable");
    static boolean isEditorOperation(String tool) { return EditorWriteBoundary.tracked(tool); }
    private static final ThreadLocal<Operation> CURRENT = new ThreadLocal<>();
    private final long advisoryTimeoutMillis;
    private final Map<String, Operation> operations = new LinkedHashMap<>();
    private final ExecutorService worker = AppExecutorUtil.createBoundedApplicationPoolExecutor("idea-mcp-platform", 4);
    private boolean disposed;

    public PlatformOperationService() { this(600_000); }
    PlatformOperationService(long advisoryTimeoutMillis) { this.advisoryTimeoutMillis = advisoryTimeoutMillis; }
    static void stage(String value) {
        Operation operation = CURRENT.get();
        if (operation != null) synchronized (operation) { operation.stage = value; }
    }

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
                operation.startedAt = System.currentTimeMillis();
            }
            CURRENT.set(operation);
            try {
                JsonObject result = action.call();
                synchronized (operation) {
                    operation.result = result;
                    operation.phase = result.has("isError") && result.get("isError").getAsBoolean() ? "failed"
                            : operation.cancelRequested ? "cancelled" : "completed";
                    operation.stage = "finished";
                    if (result.has("_meta") && result.getAsJsonObject("_meta").has(EditorWriteBoundary.META_KEY)
                            && result.getAsJsonObject("_meta").getAsJsonObject(EditorWriteBoundary.META_KEY).get("status").getAsString().equals("incomplete"))
                        operation.error = "EDITOR_WRITE_INCOMPLETE: inspect editorWrite and reconcile files before another edit";
                    else if (operation.cancelRequested && isEditorOperation(tool))
                        operation.error = "Cancellation was requested after editing started; inspect result/editorWrite. Completed changes were not rolled back.";
                }
            } catch (Throwable e) {
                synchronized (operation) {
                    operation.error = e instanceof EditorWriteBoundary.Busy || e instanceof EditorWriteBoundary.Rejected ? e.getMessage()
                            : e instanceof CancellationException || e instanceof InterruptedException ? "Operation cancelled; side effects may already have occurred" : "Platform tool failed: " + e.getClass().getSimpleName();
                    operation.phase = operation.cancelRequested ? "cancelled" : "failed";
                    operation.stage = "finished";
                }
            } finally {
                CURRENT.remove();
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
                    // Interrupting an editor between Document modification and save leaves
                    // partial unsaved changes. Once started, finish through a safe boundary.
                    if (o.future != null && (queued || !isEditorOperation(o.tool))) o.future.cancel(!queued);
                    o.error = isEditorOperation(o.tool) && !queued
                            ? "Cancellation requested; waiting for the active edit and persistence check. Do not submit another edit."
                            : "Cancellation requested; this does not undo side effects or stop business processes";
                }
            }
        }
        return o.snapshot();
    }

    @Override
    public synchronized void dispose() {
        disposed = true;
        for (Operation o : operations.values()) query(object("operationId", o.id), true);
        // Non-editor work was interrupted above; active editor work must retain its
        // opportunity to finish saving. No await or disk wait on the disposal thread.
        worker.shutdown();
        operations.clear();
    }

    private final class Operation {
        final String id = UUID.randomUUID().toString(), tool;
        String phase = "queued", error;
        String stage = "provider";
        long startedAt;
        boolean cancelRequested;
        JsonObject result;
        Future<?> future;

        Operation(String tool) {
            this.tool = tool;
        }

        synchronized JsonObject snapshot() {
            boolean inProgress = Set.of("queued", "running", "cancel_requested").contains(phase);
            JsonObject snapshot = object("accepted", true, "operationId", id, "tool", tool, "phase", phase,
                    "stage", stage, "inProgress", inProgress, "cancelRequested", cancelRequested, "error", error,
                    "deadlineExceeded", isEditorOperation(tool) && startedAt > 0 && inProgress && System.currentTimeMillis() - startedAt >= advisoryTimeoutMillis,
                    "result", result == null ? null : result.deepCopy());
            if (result != null && result.has("_meta") && result.getAsJsonObject("_meta").has(EditorWriteBoundary.META_KEY))
                snapshot.add("editorWrite", result.getAsJsonObject("_meta").get(EditorWriteBoundary.META_KEY).deepCopy());
            return snapshot;
        }
    }
}
