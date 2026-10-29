package dev.ideamcp.protocol;

import com.google.gson.*;
import java.util.Set;
import static dev.ideamcp.protocol.Json.object;

/** Stateless MCP 2025 Streamable HTTP subset: JSON responses, no sessions or server push. */
public final class RpcHandler {
    @FunctionalInterface public interface Invocation { JsonObject call(JsonObject arguments) throws Exception; }
    public record ToolCall(JsonObject descriptor, Invocation invocation) {
        public JsonObject call(JsonObject arguments) throws Exception { return invocation.call(arguments); }
    }
    public static final Set<String> VERSIONS = Set.of("2025-03-26", "2025-06-18", "2025-11-25");
    @FunctionalInterface public interface Backend {
        JsonObject call(String name, JsonObject arguments) throws Exception;
        default JsonArray tools() { return ToolCatalog.tools(); }
        default JsonObject tool(String name) {
            for (JsonElement item : tools())
                if (name.equals(item.getAsJsonObject().get("name").getAsString())) return item.getAsJsonObject();
            return null;
        }
        default ToolCall prepare(String name) {
            JsonObject descriptor = null;
            if (ToolCatalog.NAMES.contains(name)) {
                for (JsonElement item : ToolCatalog.tools())
                    if (name.equals(item.getAsJsonObject().get("name").getAsString())) descriptor = item.getAsJsonObject();
            } else descriptor = tool(name);
            return descriptor == null ? null : new ToolCall(descriptor, args -> callTool(name, args));
        }
        default JsonObject callTool(String name, JsonObject arguments) throws Exception { return result(call(name, arguments), false); }
    }
    private final Backend backend;
    public RpcHandler(Backend backend) { this.backend = backend; }

    public JsonObject handle(JsonElement input) {
        if (!input.isJsonObject()) return error(JsonNull.INSTANCE, -32600, "Expected one JSON-RPC object");
        JsonObject request = input.getAsJsonObject();
        JsonElement id = request.get("id");
        if (id != null && !id.isJsonNull() && (!id.isJsonPrimitive() || id.getAsJsonPrimitive().isBoolean()))
            return error(JsonNull.INSTANCE, -32600, "Invalid request id");
        if (!"2.0".equals(safeString(request.get("jsonrpc"))) || safeString(request.get("method")) == null)
            return error(id, -32600, "Invalid JSON-RPC request");
        String method = request.get("method").getAsString();
        JsonObject params;
        if (request.has("params") && !request.get("params").isJsonObject()) return error(id, -32602, "params must be an object");
        if (id == null) return null;
        params = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
        try {
            JsonObject result = switch (method) {
                case "initialize" -> initialize(params);
                case "ping" -> object();
                case "tools/list" -> object("tools", backend.tools());
                case "tools/call" -> call(params);
                default -> null;
            };
            return result == null ? error(id, -32601, "Unknown method: " + method) : object("jsonrpc", "2.0", "id", id, "result", result);
        } catch (IllegalArgumentException e) {
            return error(id, -32602, e.getMessage());
        } catch (RuntimeException e) {
            return error(id, -32603, "IDE tool discovery failed: " + e.getClass().getSimpleName());
        }
    }

    private JsonObject initialize(JsonObject params) {
        String requested = Arguments.required(params, "protocolVersion");
        String version = VERSIONS.contains(requested) ? requested : "2025-11-25";
        return object("protocolVersion", version, "capabilities", object("tools", object("listChanged", false)),
                "serverInfo", object("name", "idea-symbol-mcp", "version", "0.6.11"),
                "instructions", "Use list_projects first. Positions are 1-based UTF-16. Queries use current committed editor contents. Retry INDEXING or DOCUMENTS_UNCOMMITTED errors after IDEA settles. For IDE project source edits prefer apply_patch/create_new_file/reformat_file/rename_refactoring over external disk writes. These editors return operationId: poll the same get_tool_operation until inProgress=false, then inspect phase/error/result.isError and editorWrite.status=persisted before handing files to disk tools. saving/flushing/verifying, cancel_requested and deadlineExceeded never mean editing stopped. On incomplete/conflict stop and reconcile; do not retry, reload or overwrite blindly. For service startup/restart, list_run_configurations first, restart only the user-requested exact configuration ID, and poll get_run_operation until completion. A started process is not a health check. For debugger inspection, list_debug_sessions first and use its exact sessionId and current pauseId. Debug controls and breakpoint changes require the user's explicit request; poll sessions after control acceptance and never reuse an old pauseId.");
    }

    private JsonObject call(JsonObject params) {
        String name = Arguments.required(params, "name");
        ToolCall prepared;
        try (var phase = QueryTiming.phase("rpc_prepare")) { prepared = backend.prepare(name); }
        if (prepared == null) throw new IllegalArgumentException("Unknown tool: " + name);
        if (params.has("arguments") && !params.get("arguments").isJsonObject()) throw new IllegalArgumentException("arguments must be an object");
        JsonObject args = params.has("arguments") ? params.getAsJsonObject("arguments") : object();
        try {
            // Validate against the published schema before invoking PSI (reject typos too).
            try (var phase = QueryTiming.phase("rpc_validate")) {
                SchemaValidator.validate(prepared.descriptor().getAsJsonObject("inputSchema"), args);
                if (ToolCatalog.NAMES.contains(name)) validate(name, args);
            }
            try (var phase = QueryTiming.phase("rpc_invoke_total")) { return prepared.call(args); }
        } catch (Exception e) {
            return result(object("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), true);
        }
    }

    private static void validate(String name, JsonObject args) {
        for (JsonElement element : ToolCatalog.tools()) {
            JsonObject tool = element.getAsJsonObject();
            if (!name.equals(tool.get("name").getAsString())) continue;
            JsonObject schema = tool.getAsJsonObject("inputSchema");
            JsonObject properties = schema.getAsJsonObject("properties");
            for (String key : args.keySet()) {
                if (!properties.has(key)) throw new IllegalArgumentException("Unknown argument: " + key);
                JsonObject property = properties.getAsJsonObject(key);
                switch (property.get("type").getAsString()) {
                    case "string" -> {
                        String value = Arguments.required(args, key);
                        if (property.has("enum") && java.util.stream.StreamSupport.stream(property.getAsJsonArray("enum").spliterator(), false)
                                .noneMatch(allowed -> allowed.getAsString().equals(value)))
                            throw new IllegalArgumentException("Invalid value for " + key);
                    }
                    case "boolean" -> Arguments.bool(args, key, false);
                    case "integer" -> Arguments.integer(args, key, 0, property.has("minimum") ? property.get("minimum").getAsInt() : 0,
                            property.has("maximum") ? property.get("maximum").getAsInt() : Integer.MAX_VALUE);
                }
            }
            for (JsonElement key : schema.getAsJsonArray("required")) if (!args.has(key.getAsString())) throw new IllegalArgumentException("Missing " + key.getAsString());
            if (name.equals("read_symbols") && args.has("file") == args.has("query")) throw new IllegalArgumentException("Supply exactly one of file/query");
            if (name.equals("control_debugger")) {
                if (Arguments.required(args,"action").equals("pause")) {
                    if (args.has("pauseId")) throw new IllegalArgumentException("pauseId must be omitted for pause");
                } else Arguments.required(args,"pauseId");
            }
        }
    }
    private static JsonObject result(JsonObject data, boolean error) {
        return object("content", new Object[]{object("type", "text", "text", Json.GSON.toJson(data))}, "structuredContent", data, "isError", error);
    }
    private static String safeString(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }
    public static JsonObject error(JsonElement id, int code, String message) {
        return object("jsonrpc", "2.0", "id", id == null ? JsonNull.INSTANCE : id, "error", object("code", code, "message", message));
    }
}
