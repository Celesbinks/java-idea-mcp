package dev.ideamcp;

import dev.ideamcp.protocol.*;
import org.junit.Test;
import java.net.URI;
import java.net.http.*;
import java.util.concurrent.*;
import static dev.ideamcp.protocol.Json.object;
import static org.junit.Assert.*;

public final class QueryTimingTest {
    @Test public void disabledAndNestedContextsDoNotLeak() throws Exception {
        assertNull(QueryTiming.current());
        try (var outer = QueryTiming.open(true)) {
            var context = QueryTiming.current();
            try (var span = QueryTiming.phase("outer")) { }
            try (var disabled = QueryTiming.open(false)) { assertNull(QueryTiming.current()); }
            assertSame(context, QueryTiming.current());
            var executor = Executors.newSingleThreadExecutor();
            try {
                executor.submit(() -> {
                    assertNull(QueryTiming.current()); var previous = QueryTiming.attach(context);
                    try (var span = QueryTiming.phase("worker")) { }
                    finally { QueryTiming.attach(previous); }
                    assertNull(QueryTiming.current());
                }).get(5, TimeUnit.SECONDS);
            } finally { executor.shutdownNow(); }
            assertEquals(2, context.snapshot().size());
            assertTrue(context.header(), context.header().matches("outer;dur=\\d+\\.\\d{3},worker;dur=\\d+\\.\\d{3}"));
        }
        assertNull(QueryTiming.current());
    }

    @Test public void durationIsCommittedOnceAndNamesAreFixedTokens() {
        try (var scope = QueryTiming.open(true)) {
            var span = QueryTiming.phase("one"); span.close(); long first = QueryTiming.current().snapshot().get("one"); span.close();
            assertEquals(first, QueryTiming.current().snapshot().get("one").longValue());
            assertThrows(IllegalArgumentException.class, () -> QueryTiming.phase("unsafe/path or value"));
        }
    }

    @Test public void headersAreOptInAuthenticatedAndBodyPreserving() throws Exception {
        String token = "fixture_timing_AAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        String sentinel = "fixture_payload_never_in_metrics";
        var rpc = new RpcHandler((name, args) -> object("value", sentinel));
        try (var plain = new McpHttpServer(0, token, rpc, false); var measured = new McpHttpServer(0, token, rpc, true);
             var client = HttpClient.newHttpClient()) {
            plain.start(); measured.start();
            String body = object("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", object("name", "list_projects", "arguments", object())).toString();
            var a = send(client, plain.url(), token, body); var b = send(client, measured.url(), token, body);
            assertEquals(200, b.statusCode()); assertEquals(a.body(), b.body());
            assertTrue(a.headers().firstValue("Server-Timing").isEmpty());
            String header = b.headers().firstValue("Server-Timing").orElseThrow();
            assertTrue(header, header.matches("[a-z_]+;dur=\\d+\\.\\d{3}(,[a-z_]+;dur=\\d+\\.\\d{3})*"));
            assertTrue(header.contains("rpc_prepare;dur=")); assertTrue(header.contains("http_serialize;dur="));
            assertFalse(header.contains(token)); assertFalse(header.contains(sentinel));
            var denied = send(client, measured.url(), "invalid", body);
            assertEquals(401, denied.statusCode()); assertTrue(denied.headers().firstValue("Server-Timing").isEmpty());
        }
        assertNull(QueryTiming.current());
    }
    private static HttpResponse<String> send(HttpClient client, String url, String token, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                .header("Accept", "application/json, text/event-stream").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
