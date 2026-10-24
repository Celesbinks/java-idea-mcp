package dev.ideamcp.protocol;

import com.google.gson.*;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * A client-owned stdio process. Credentials are supplied through its environment, never arguments.
 */
public final class StdioBridge {
    private StdioBridge() {
    }

    public static void main(String[] args) {
        try {
            URI endpoint = URI.create(System.getenv("IDEA_JAVA_MCP_URL"));
            String token = System.getenv("IDEA_JAVA_MCP_TOKEN");
            if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost()) || !"/mcp".equals(endpoint.getPath()) || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null || token == null || !token.matches("[A-Za-z0-9_-]{32,256}"))
                throw new IOException("Invalid bridge settings");
            relay(endpoint, token, System.in, System.out);
        } catch (Exception e) {
            System.err.println("Java MCP bridge failed (" + e.getClass().getSimpleName() + "). Check the IDE and client configuration.");
            System.exit(1);
        }
    }

    public static void relay(URI endpoint, String token, InputStream input, OutputStream output) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            String protocol = "2025-11-25";
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            for (int b; (b = input.read()) != -1; ) {
                if (b != '\n') {
                    if (line.size() >= 1024 * 1024) throw new IOException("Request too large");
                    line.write(b);
                    continue;
                }
                String request = line.toString(StandardCharsets.UTF_8).strip();
                line.reset();
                if (request.isEmpty()) continue;
                JsonObject json = parse(request).getAsJsonObject();
                boolean initialize = json.has("method") && "initialize".equals(json.get("method").getAsString());
                HttpRequest http = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", protocol).header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.ofString(request)).build();
                HttpResponse<String> response = client.send(http, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 202 || response.statusCode() == 204) continue;
                if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
                JsonElement reply = parse(response.body());
                String body = reply.toString();
                if (initialize && reply.isJsonObject() && reply.getAsJsonObject().has("result"))
                    protocol = reply.getAsJsonObject().getAsJsonObject("result").get("protocolVersion").getAsString();
                output.write((body + "\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
            if (line.size() != 0) throw new IOException("Incomplete stdio message");
        }
    }

    private static JsonElement parse(String text) throws IOException {
        var reader = new com.google.gson.stream.JsonReader(new StringReader(text));
        reader.setStrictness(Strictness.STRICT);
        JsonElement value = JsonParser.parseReader(reader);
        if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) throw new IOException("Trailing JSON data");
        return value;
    }
}
