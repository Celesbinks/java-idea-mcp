package dev.ideamcp.protocol;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.util.concurrent.*;

/** Loopback-only, bearer-authenticated Streamable HTTP transport. */
public final class McpHttpServer implements AutoCloseable {
    private static final int MAX_BODY = 1024 * 1024;
    private final HttpServer server;
    private final ExecutorService executor;
    private final byte[] authorization;
    private final RpcHandler rpc;

    public McpHttpServer(int port, String token, RpcHandler rpc) throws IOException {
        if (token == null || token.length() < 32) throw new IllegalArgumentException("A strong bearer token is required");
        this.rpc = rpc;
        authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16);
        executor = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), runnable -> {
            Thread thread = new Thread(runnable, "idea-mcp-http");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(executor);
        server.createContext("/mcp", this::handle);
    }
    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    public String url() { return "http://127.0.0.1:" + port() + "/mcp"; }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestURI().getPath().equals("/mcp") || exchange.getRequestURI().getRawQuery() != null) { send(exchange, 404, null); return; }
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (!allowedAuthority(host)) { send(exchange, 403, null); return; }
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && !allowedOrigin(origin)) { send(exchange, 403, null); return; }
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !MessageDigest.isEqual(authorization, auth.getBytes(StandardCharsets.UTF_8))) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                send(exchange, 401, null); return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.getResponseHeaders().set("Allow", "POST");
                send(exchange, 405, null); return;
            }
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) { send(exchange, 415, null); return; }
            String accept = String.join(",", exchange.getRequestHeaders().getOrDefault("Accept", java.util.List.of()));
            if (!accept.contains("application/json") || !accept.contains("text/event-stream")) { send(exchange, 406, null); return; }
            String version = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
            if (version != null && !RpcHandler.VERSIONS.contains(version)) {
                send(exchange, 400, RpcHandler.error(null, -32600, "Unsupported MCP-Protocol-Version")); return;
            }
            byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) { send(exchange, 413, null); return; }
            JsonElement input;
            try {
                String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                JsonReader reader = new JsonReader(new StringReader(json));
                reader.setStrictness(Strictness.STRICT);
                input = JsonParser.parseReader(reader);
                if (reader.peek() != JsonToken.END_DOCUMENT || json.isBlank()) throw new JsonParseException("Expected a single JSON value");
            } catch (JsonParseException | IOException e) {
                send(exchange, 400, RpcHandler.error(null, -32700, "Invalid JSON")); return;
            }
            JsonObject response = rpc.handle(input);
            send(exchange, response == null ? 202 : 200, response);
        }
    }
    private boolean allowedAuthority(String authority) {
        return ("127.0.0.1:" + port()).equalsIgnoreCase(authority) || ("localhost:" + port()).equalsIgnoreCase(authority);
    }
    private boolean allowedOrigin(String origin) {
        try {
            URI uri = URI.create(origin);
            return "http".equals(uri.getScheme()) && uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (uri.getPath().isEmpty() || uri.getPath().equals("/")) && allowedAuthority(uri.getAuthority());
        } catch (IllegalArgumentException e) { return false; }
    }
    private static void send(HttpExchange exchange, int status, JsonObject body) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (body == null) { exchange.sendResponseHeaders(status, -1); return; }
        byte[] bytes = Json.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
    @Override public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
