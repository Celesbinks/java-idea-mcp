package dev.ideamcp.protocol;

import com.sun.net.httpserver.*;
import jdk.jfr.*;
import jdk.jfr.consumer.*;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static dev.ideamcp.protocol.Json.object;
import static org.junit.Assert.*;

public final class HttpCompletionTest {
    private static final String TOKEN = "fixture_completion_AAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String SENTINEL = "fixture_private_payload_never_in_event";
    private static String body() { return object("jsonrpc", "2.0", "id", SENTINEL, "method", "tools/call", "params", object("name", "list_projects", "arguments", object())).toString(); }
    private static Recording recording() throws Exception {
        return new Recording(Configuration.create(Path.of("scripts/http-timing.jfc")));
    }
    @Test public void onlyAuthorizedEnabledRequestsProduceNumericCompletionEvents() throws Exception {
        Path file = Files.createTempFile("mcp-completion-", ".jfr");
        try (var recording = recording(); var client = HttpClient.newHttpClient();
             var measured = new McpHttpServer(0, TOKEN, new RpcHandler((name, args) -> object("value", SENTINEL)), true);
             var disabled = new McpHttpServer(0, TOKEN, new RpcHandler((name, args) -> object("value", SENTINEL)), false)) {
            recording.start(); measured.start(); disabled.start();
            var plain = send(client, disabled.url(), TOKEN, body());
            assertTrue(plain.headers().firstValue("X-Java-Mcp-Timing-Id").isEmpty());
            var denied = send(client, measured.url(), "invalid", body());
            assertEquals(401, denied.statusCode()); assertTrue(denied.headers().firstValue("Server-Timing").isEmpty());
            assertTrue(denied.headers().firstValue("X-Java-Mcp-Timing-Id").isEmpty());
            Map<Long, Boolean> ids = new HashMap<>();
            for (int i = 0; i < 4; i++) {
                boolean valid = i != 2;
                var reply = send(client, measured.url(), TOKEN, valid ? body() : "{");
                assertEquals(valid ? 200 : 400, reply.statusCode());
                if (valid) assertEquals(plain.body(), reply.body());
                long id = Long.parseLong(reply.headers().firstValue("X-Java-Mcp-Timing-Id").orElseThrow());
                assertNull(ids.put(id, valid));
                String header = reply.headers().firstValue("Server-Timing").orElseThrow();
                assertTrue(header, header.contains("http_executor_wait;dur="));
                assertTrue(header, header.contains("http_task_to_handler;dur="));
                assertTrue(header, header.contains("http_admission;dur="));
                assertFalse(header.contains("http_write_body")); assertFalse(header.contains("http_close"));
            }
            List<RecordedEvent> events = awaitEvents(recording, file, 4);
            assertEquals(4, events.size());
            for (var event : events) {
                assertTrue(ids.containsKey(event.getLong("requestId")));
                assertTrue(event.getBoolean("responseCompleted"));
                for (String name : List.of("executorWait", "taskToHandler", "admission", "read", "parse", "serialize", "headersPrepare", "headersSend", "bodyWrite", "exchangeClose"))
                    assertTrue(name, event.getLong(name) >= 0);
                assertEquals(event.getLong("taskThroughClose"), event.getLong("executorWait") + event.getLong("taskToHandler") + event.getLong("handlerThroughClose"));
                assertEquals(-1, event.getLong("provider"));
                assertEquals(ids.get(event.getLong("requestId")), event.getLong("rpc") >= 0);
                assertFalse(event.toString().contains(TOKEN)); assertFalse(event.toString().contains(SENTINEL));
                for (var field : event.getEventType().getFields()) assertNotEquals("java.lang.String", field.getTypeName());
                assertNull(event.getStackTrace());
            }
        } finally { Files.deleteIfExists(file); }
        assertNull(QueryTiming.current());
    }
    @Test public void failedBodyWriteStillClosesAndRecordsIncompleteResponse() throws Exception {
        Path file = Files.createTempFile("mcp-failed-write-", ".jfr");
        try (var recording = recording(); var server = new McpHttpServer(0, TOKEN, new RpcHandler((name, args) -> object("value", SENTINEL)), true)) {
            recording.start();
            var exchange = new BrokenExchange(server.port());
            var handle = McpHttpServer.class.getDeclaredMethod("handle", HttpExchange.class); handle.setAccessible(true);
            var failure = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> handle.invoke(server, exchange));
            assertTrue(failure.getCause() instanceof IOException); assertTrue(exchange.closed);
            var events = awaitEvents(recording, file, 1); assertEquals(1, events.size());
            var event = events.get(0); assertFalse(event.getBoolean("responseCompleted"));
            assertTrue(event.getLong("bodyWrite") >= 0); assertTrue(event.getLong("exchangeClose") >= 0);
            assertEquals(-1, event.getLong("executorWait"));
            assertNull(QueryTiming.current());
        } finally { Files.deleteIfExists(file); }
    }
    private static List<RecordedEvent> awaitEvents(Recording recording, Path file, int count) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        do {
            recording.dump(file);
            var events = RecordingFile.readAllEvents(file);
            assertTrue(events.toString(), events.stream().allMatch(e -> e.getEventType().getName().equals(HttpTimingEvent.NAME)));
            if (events.size() >= count) return events;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Completion events missing");
    }
    private static HttpResponse<String> send(HttpClient client, String url, String token, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                .header("Accept", "application/json, text/event-stream").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static final class BrokenExchange extends HttpExchange {
        final Headers request = new Headers(), response = new Headers();
        boolean closed; int status; final int port;
        BrokenExchange(int port) { this.port = port; request.set("Host", "127.0.0.1:" + port); request.set("Authorization", "Bearer " + TOKEN); request.set("Content-Type", "application/json"); request.set("Accept", "application/json, text/event-stream"); }
        @Override public Headers getRequestHeaders() { return request; }
        @Override public Headers getResponseHeaders() { return response; }
        @Override public URI getRequestURI() { return URI.create("/mcp"); }
        @Override public String getRequestMethod() { return "POST"; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { closed = true; }
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(body().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        @Override public OutputStream getResponseBody() { return new OutputStream() { @Override public void write(int b) throws IOException { throw new IOException("fixture write failed"); } }; }
        @Override public void sendResponseHeaders(int code, long length) { status = code; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 12345); }
        @Override public int getResponseCode() { return status; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", port); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) { }
        @Override public void setStreams(InputStream input, OutputStream output) { }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}
