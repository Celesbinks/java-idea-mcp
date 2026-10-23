package dev.ideamcp;

import com.google.gson.*;
import dev.ideamcp.protocol.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.ideamcp.protocol.Json.object;

/** Runs without starting an IDE: exercises real HTTP and JSON-RPC boundaries. */
public final class ProtocolSelfTest {
    private static int checks;
    private static final String TOKEN = "test-token-012345678901234567890123456789";
    public static void main(String[] args) throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        RpcHandler handler = new RpcHandler((name, arguments) -> {
            invocations.incrementAndGet();
            if (arguments.has("project")) throw new IllegalArgumentException("PROJECT_NOT_FOUND");
            return object("projects", new Object[]{});
        });
        JsonObject initialized = handler.handle(request(1, "initialize", object("protocolVersion", "2025-11-25")));
        check(initialized.getAsJsonObject("result").get("protocolVersion").getAsString().equals("2025-11-25"), "version negotiation");
        initialized = handler.handle(request(1, "initialize", object("protocolVersion", "2099-01-01")));
        check(initialized.getAsJsonObject("result").get("protocolVersion").getAsString().equals("2025-11-25"), "unsupported version fallback");
        for (String version : RpcHandler.VERSIONS) check(handler.handle(request(1, "initialize", object("protocolVersion", version))).getAsJsonObject("result").get("protocolVersion").getAsString().equals(version), "supported version " + version);
        check(handler.handle(request(2, "tools/list", object())).getAsJsonObject("result").getAsJsonArray("tools").size() == 19, "tool discovery");
        JsonObject restartTool = null;
        for (JsonElement tool : ToolCatalog.tools()) if (tool.getAsJsonObject().get("name").getAsString().equals("restart_run_configuration")) restartTool = tool.getAsJsonObject();
        check(restartTool != null && !restartTool.getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean(), "restart is advertised as a mutation");
        check(restartTool.getAsJsonObject("annotations").get("destructiveHint").getAsBoolean(), "restart declares process disruption");
        check(!restartTool.getAsJsonObject("annotations").get("idempotentHint").getAsBoolean(), "restart is not idempotent");
        check(handler.handle(object("jsonrpc", "2.0", "method", "notifications/initialized")) == null, "notifications have no response");
        check(code(handler.handle(new JsonArray())) == -32600, "batches rejected");
        check(code(handler.handle(request(true, "ping", object()))) == -32600, "boolean ids rejected");
        check(code(handler.handle(request(1, "missing", object()))) == -32601, "unknown method");
        check(code(handler.handle(request(1, "tools/call", object("name", "missing")))) == -32602, "unknown tool");
        check(code(handler.handle(request(1, "tools/call", object("name", "read_symbols", "arguments", 42)))) == -32602, "non-object arguments");
        int before = invocations.get();
        check(toolError(handler, object("file", "A.java", "limit", 1.5)), "fractional integer rejected");
        check(toolError(handler, object("file", "A.java", "limit", "10")), "string integer rejected");
        check(toolError(handler, object("file", "A.java", "includeLibraries", "true")), "string boolean rejected");
        check(toolError(handler, object("file", "A.java", "limit", 201)), "limit bounded");
        check(toolError(handler, object("file", "A.java", "query", "A")), "mutually exclusive selectors");
        check(toolError(handler, object()), "missing selector rejected");
        check(toolError(handler, object("file", "A.java", "unknown", true)), "unknown argument rejected");
        check(toolError(handler, object("file", JsonNull.INSTANCE)), "null argument rejected");
        check(invocations.get() == before, "invalid inputs never reach PSI backend");
        for (JsonObject invalid : new JsonObject[]{object(), object("configurationId", "Application.Test", "mode", "invalid"), object("configurationId", "Application.Test", "stopTimeoutSeconds", 0)}) {
            JsonObject response = handler.handle(request(1, "tools/call", object("name", "restart_run_configuration", "arguments", invalid)));
            check(response.getAsJsonObject("result").get("isError").getAsBoolean(), "invalid restart input rejected");
        }
        check(invocations.get() == before, "invalid restart input never reaches execution backend");
        for (String readTool : new String[]{"get_diagnostics", "find_callers", "find_callees", "read_run_logs", "list_debug_sessions", "read_debug_threads", "read_debug_stack", "read_debug_variables", "list_breakpoints"}) {
            JsonObject metadata = null;
            for (JsonElement item : ToolCatalog.tools()) if (item.getAsJsonObject().get("name").getAsString().equals(readTool)) metadata = item.getAsJsonObject();
            check(metadata != null && metadata.getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean(), readTool + " is read-only");
            check(!metadata.getAsJsonObject("annotations").get("destructiveHint").getAsBoolean(), readTool + " is non-destructive");
        }
        for (Object[] invalid : new Object[][]{
                {"get_diagnostics", object()}, {"get_diagnostics", object("file", "A.java", "severity", "info")},
                {"find_callers", object("file", "A.java", "line", 0, "column", 1)}, {"find_callees", object("file", "A.java", "line", 1, "column", 1, "includeDeclaration", true)},
                {"read_run_logs", object()}, {"read_run_logs", object("configurationId", "cfg", "cursor", 1)},
                {"read_run_logs", object("configurationId", "cfg", "maxChars", 1)}, {"read_run_logs", object("configurationId", "cfg", "maxChars", 20001)}}) {
            JsonObject response = handler.handle(request(1, "tools/call", object("name", invalid[0], "arguments", invalid[1])));
            check(response.getAsJsonObject("result").get("isError").getAsBoolean(), "invalid " + invalid[0] + " input rejected");
        }
        check(invocations.get() == before, "invalid 0.3 inputs never reach backend");
        for (JsonElement item : ToolCatalog.tools()) {
            JsonObject tool=item.getAsJsonObject();String toolName=tool.get("name").getAsString();
            if (!java.util.Set.of("control_debugger","set_breakpoint","remove_breakpoint").contains(toolName)) continue;
            JsonObject annotations=tool.getAsJsonObject("annotations");
            check(!annotations.get("readOnlyHint").getAsBoolean(),toolName+" mutates debugger state");
            check(annotations.get("destructiveHint").getAsBoolean(),toolName+" can disrupt debug execution");
            check(!annotations.get("idempotentHint").getAsBoolean(),toolName+" must not be retried blindly");
        }
        for(Object[] invalid:new Object[][]{
                {"read_debug_threads",object("sessionId","s")},
                {"read_debug_stack",object("sessionId","s","pauseId","p")},
                {"read_debug_variables",object("sessionId","s","pauseId","p","threadId","t","frameIndex",-1)},
                {"read_debug_variables",object("sessionId","s","pauseId","p","threadId","t","maxStringLength",1001)},
                {"control_debugger",object("sessionId","s","action","stop")},
                {"control_debugger",object("sessionId","s","action","resume")},
                {"control_debugger",object("sessionId","s","action","pause","pauseId","p")},
                {"set_breakpoint",object("file","A.java","line",0)},
                {"set_breakpoint",object("file","A.java","line",1,"condition","run()")},
                {"remove_breakpoint",object()}}){
            JsonObject response=handler.handle(request(1,"tools/call",object("name",invalid[0],"arguments",invalid[1])));
            check(response.getAsJsonObject("result").get("isError").getAsBoolean(),"invalid "+invalid[0]+" input rejected");
        }
        check(invocations.get()==before,"invalid 0.4 inputs never reach debugger backend");
        JsonObject failure = handler.handle(request(4, "tools/call", object("name", "read_symbols", "arguments", object("file", "A.java", "project", "missing"))));
        check(failure.getAsJsonObject("result").get("isError").getAsBoolean(), "backend errors are MCP tool errors");
        try (McpHttpServer server = new McpHttpServer(0, TOKEN, handler); HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            server.start();
            URI endpoint = URI.create(server.url());
            String ping = Json.GSON.toJson(request(7, "ping", object()));
            check(send(client, endpoint, "POST", ping, false, null, "application/json", true).statusCode() == 401, "authentication required");
            check(send(client, endpoint, "POST", ping, true, "https://malicious.example", "application/json", true).statusCode() == 403, "remote origin rejected");
            check(send(client, endpoint, "POST", ping, true, "null", "application/json", true).statusCode() == 403, "opaque origin rejected");
            check(send(client, endpoint, "POST", ping, true, "http://localhost:" + server.port(), "application/json", true).statusCode() == 200, "same-server origin accepted");
            check(send(client, endpoint, "GET", "", true, null, "application/json", true).statusCode() == 405, "GET has no SSE stream");
            check(send(client, endpoint, "DELETE", "", true, null, "application/json", true).statusCode() == 405, "stateless transport rejects session delete");
            check(send(client, endpoint, "POST", ping, true, null, "text/plain", true).statusCode() == 415, "JSON content type required");
            check(send(client, endpoint, "POST", ping, true, null, "application/json", false).statusCode() == 406, "both response MIME types required");
            var response = send(client, endpoint, "POST", ping, true, null, "application/json", true);
            check(response.statusCode() == 200 && JsonParser.parseString(response.body()).getAsJsonObject().get("id").getAsInt() == 7, "HTTP round trip preserves id");
            check(response.headers().firstValue("Cache-Control").orElse("").equals("no-store"), "no response caching");
            check(send(client, endpoint, "POST", "{broken", true, null, "application/json", true).statusCode() == 400, "parse errors are HTTP 400");
            check(send(client, endpoint, "POST", "{'jsonrpc':'2.0','id':1,'method':'ping'}", true, null, "application/json", true).statusCode() == 400, "non-JSON quoting rejected");
            check(send(client, endpoint, "POST", ping + " {}", true, null, "application/json", true).statusCode() == 400, "trailing JSON values rejected");
            check(send(client, endpoint, "POST", " ", true, null, "application/json", true).statusCode() == 400, "empty JSON rejected");
            response = send(client, endpoint, "POST", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", true, null, "application/json", true);
            check(response.statusCode() == 202 && response.body().isEmpty(), "notifications accepted with empty 202");
            check(send(client, URI.create(server.url() + "/other"), "POST", ping, true, null, "application/json", true).statusCode() == 404, "endpoint path exact");
            check(send(client, endpoint, "POST", " ".repeat(1024 * 1024 + 1), true, null, "application/json", true).statusCode() == 413, "request body bounded");
            var unsupported = HttpRequest.newBuilder(endpoint).header("Authorization", "Bearer " + TOKEN).header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2099-01-01").POST(HttpRequest.BodyPublishers.ofString(ping)).build();
            check(client.send(unsupported, HttpResponse.BodyHandlers.ofString()).statusCode() == 400, "unsupported protocol header rejected");
        }
        System.out.println("Protocol/HTTP self-tests passed: " + checks + " assertions");
    }
    private static JsonObject request(Object id, String method, JsonObject params) { return object("jsonrpc", "2.0", "id", id, "method", method, "params", params); }
    private static int code(JsonObject response) { return response.getAsJsonObject("error").get("code").getAsInt(); }
    private static boolean toolError(RpcHandler handler, JsonObject args) {
        return handler.handle(request(1, "tools/call", object("name", "read_symbols", "arguments", args))).getAsJsonObject("result").get("isError").getAsBoolean();
    }
    private static void check(boolean passed, String message) { checks++; if (!passed) throw new AssertionError(message); }
    private static HttpResponse<String> send(HttpClient client, URI uri, String method, String body, boolean auth, String origin, String contentType, boolean accept) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).header("Content-Type", contentType);
        if (auth) builder.header("Authorization", "Bearer " + TOKEN);
        if (accept) builder.header("Accept", "application/json, text/event-stream");
        if (origin != null) builder.header("Origin", origin);
        return client.send(builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
