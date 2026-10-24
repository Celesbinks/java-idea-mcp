package dev.ideamcp;

import com.google.gson.*;
import com.intellij.openapi.application.ApplicationManager;
import dev.ideamcp.protocol.Arguments;
import dev.ideamcp.protocol.RpcHandler;
import dev.ideamcp.protocol.ToolCatalog;

import java.util.Set;
import java.util.concurrent.*;

/**
 * Routes code reads to background read actions and execution operations to the IDE UI thread.
 */
final class IdeaToolBackend implements RpcHandler.Backend {
    private static final Set<String> RUN_TOOLS = Set.of("list_run_configurations", "restart_run_configuration", "get_run_operation", "read_run_logs");
    private final JavaCodeTools code = new JavaCodeTools();

    @Override
    public JsonArray tools() {
        JsonArray catalog = ToolCatalog.tools();
        Set<String> seen = new java.util.HashSet<>(ToolCatalog.NAMES);
        for (PlatformToolProvider provider : PlatformToolProvider.EP.getExtensionList())
            for (JsonElement item : provider.tools()) {
                String name = item.getAsJsonObject().get("name").getAsString();
                if (seen.add(name)) catalog.add(item);
            }
        return catalog;
    }

    @Override
    public JsonObject tool(String name) {
        if (ToolCatalog.NAMES.contains(name)) {
            for (JsonElement item : ToolCatalog.tools())
                if (name.equals(item.getAsJsonObject().get("name").getAsString())) return item.getAsJsonObject();
            return null;
        }
        for (PlatformToolProvider provider : PlatformToolProvider.EP.getExtensionList()) {
            JsonObject descriptor = provider.tool(name);
            if (descriptor != null) return descriptor;
        }
        return null;
    }

    @Override
    public JsonObject callTool(String name, JsonObject args) throws Exception {
        if (ToolCatalog.NAMES.contains(name)) return RpcHandler.Backend.super.callTool(name, args);
        RpcHandler.ToolCall prepared = prepare(name);
        if (prepared == null) throw new IllegalArgumentException("PLATFORM_TOOL_UNAVAILABLE: " + name);
        return prepared.call(args);
    }

    @Override
    public RpcHandler.ToolCall prepare(String name) {
        if (ToolCatalog.NAMES.contains(name)) return RpcHandler.Backend.super.prepare(name);
        for (PlatformToolProvider provider : PlatformToolProvider.EP.getExtensionList()) {
            RpcHandler.ToolCall target = provider.prepare(name);
            if (target == null) continue;
            return new RpcHandler.ToolCall(target.descriptor(), args -> {
                Callable<JsonObject> invoke = () -> {
                    if (PlatformToolProvider.EP.getExtensionList().stream().noneMatch(active -> active == provider))
                        throw new IllegalArgumentException("PLATFORM_TOOL_UNAVAILABLE: " + name);
                    return target.call(args);
                };
                if (PlatformOperationService.LONG_TOOLS.contains(name)) {
                    JsonObject accepted = ApplicationManager.getApplication().getService(PlatformOperationService.class).submit(name, invoke);
                    return dev.ideamcp.protocol.Json.object("content", new Object[]{dev.ideamcp.protocol.Json.object("type", "text", "text", dev.ideamcp.protocol.Json.GSON.toJson(accepted))}, "structuredContent", accepted, "isError", false);
                }
                return invoke.call();
            });
        }
        return null;
    }

    @Override
    public JsonObject call(String name, JsonObject arguments) throws Exception {
        if (name.equals("get_tool_operation") || name.equals("cancel_tool_operation"))
            return ApplicationManager.getApplication().getService(PlatformOperationService.class).query(arguments, name.equals("cancel_tool_operation"));
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
            try {
                result.complete(action.call());
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        try {
            return result.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            result.cancel(false);
            throw new IllegalArgumentException("IDE_BUSY: IDEA did not handle the request within 5 seconds; inspect current run operations before retrying a restart");
        } catch (InterruptedException e) {
            result.cancel(false);
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }
}
