package dev.ideamcp;

import dev.ideamcp.protocol.*;
import org.junit.Test;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static dev.ideamcp.protocol.Json.object;
import static org.junit.Assert.*;

public class StdioBridgeTest {
    @Test
    public void packagedLauncherRunsAsASeparateJavaProcessAndNegotiatesProtocol() throws Exception {
        String token = "fixture_token_AAAAAAAAAAAAAAAAAAAAAA";
        try (var server = new McpHttpServer(0, token, new RpcHandler((name, args) -> object("fixture", true)))) {
            server.start();
            var connection = AutoClientService.connection(server.url(), token);
            var launcher = connection.launcher();
            var command = new java.util.ArrayList<String>();
            command.add(launcher.command());
            command.addAll(launcher.args());
            var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("IDEA_JAVA_MCP_URL", server.url());
            builder.environment().put("IDEA_JAVA_MCP_TOKEN", token);
            Process child = builder.start();
            try {
                try (var output = new PrintWriter(new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    output.println(object("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", object("protocolVersion", "2024-11-05")));
                    output.println(object("jsonrpc", "2.0", "id", 2, "method", "tools/list"));
                }
                var received = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        return new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
                assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(0, child.exitValue());
                String result = received.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(result.contains("2025-11-25"));
                assertTrue(result.contains("read_symbols"));
                assertFalse(result.contains(token));
            } finally {
                if (child.isAlive()) child.destroyForcibly().waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void relaysInitializeNotificationsAndToolsThroughAuthenticatedHttp() throws Exception {
        String token = "fixture_token_AAAAAAAAAAAAAAAAAAAAAA";
        try (var server = new McpHttpServer(0, token, new RpcHandler((name, args) -> object("fixture", true)))) {
            server.start();
            String requests = object("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", object("protocolVersion", "2025-11-25")) + "\n" + object("jsonrpc", "2.0", "method", "notifications/initialized") + "\n" + object("jsonrpc", "2.0", "id", 2, "method", "tools/list") + "\n";
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            StdioBridge.relay(URI.create(server.url()), token, new ByteArrayInputStream(requests.getBytes(StandardCharsets.UTF_8)), output);
            String[] responses = output.toString(StandardCharsets.UTF_8).strip().split("\n");
            assertEquals(2, responses.length);
            assertTrue(responses[0].contains("0.6.8"));
            assertTrue(responses[1].contains("read_symbols"));
            assertFalse(output.toString(StandardCharsets.UTF_8).contains(token));
        }
    }
}
