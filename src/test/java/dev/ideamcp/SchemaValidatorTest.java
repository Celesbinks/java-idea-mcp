package dev.ideamcp;

import com.google.gson.*;
import dev.ideamcp.protocol.*;
import org.junit.Test;

import static dev.ideamcp.protocol.Json.object;
import static org.junit.Assert.*;

public final class SchemaValidatorTest {
    @Test
    public void nestedArraysReferencesNullAndBounds() {
        JsonObject schema = JsonParser.parseString("""
                {"type":"object","properties":{"files":{"type":"array","minItems":1,"items":{"$ref":"#/$defs/file"}},"scope":{"type":["string","null"]}},"required":["files"],"$defs":{"file":{"type":"object","properties":{"line":{"type":"integer","minimum":1},"path":{"type":"string","minLength":1}},"required":["path","line"],"additionalProperties":false}}}
                """).getAsJsonObject();
        SchemaValidator.validate(schema, object("files", new Object[]{object("path", "A.java", "line", 1)}, "scope", JsonNull.INSTANCE));
        for (JsonObject invalid : new JsonObject[]{object("files", new Object[]{}), object("files", new Object[]{object("path", "A.java", "line", 0)}), object("files", new Object[]{object("path", "A.java", "line", 1.5)}), object("files", new Object[]{object("path", "", "line", 1)}), object("files", new Object[]{object("path", "A.java", "line", 1, "typo", true)}), object("files", new String[]{"wrong"})})
            assertThrows(IllegalArgumentException.class, () -> SchemaValidator.validate(schema, invalid));
    }

    @Test
    public void rejectsUnknownRootKeysEvenWhenProviderOmitsAdditionalProperties() {
        assertThrows(IllegalArgumentException.class, () -> SchemaValidator.validate(object("type", "object", "properties", object("files", object("type", "array"))), object("typo", 1)));
    }

    @Test
    public void fullPlatformResultsAreNotWrappedAgainAndErrorsSurvive() {
        JsonObject descriptor = object("name", "platform_probe", "inputSchema", object("type", "object", "properties", object("files", object("type", "array", "items", object("type", "string"))), "required", new String[]{"files"}));
        RpcHandler.Backend backend = new RpcHandler.Backend() {
            @Override
            public JsonObject call(String name, JsonObject args) {
                throw new AssertionError("Must use full result dispatch");
            }

            @Override
            public JsonArray tools() {
                JsonArray a = new JsonArray();
                a.add(descriptor);
                return a;
            }

            @Override
            public JsonObject callTool(String name, JsonObject args) {
                return object("content", new Object[]{object("type", "text", "text", "probe failure")}, "isError", true);
            }
        };
        RpcHandler rpc = new RpcHandler(backend);
        JsonObject result = rpc.handle(request(object("files", new String[]{"A.java"}))).getAsJsonObject("result");
        assertTrue(result.get("isError").getAsBoolean());
        assertEquals("probe failure", result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
        assertFalse(result.has("structuredContent"));
        result = rpc.handle(request(object("files", new int[]{1}))).getAsJsonObject("result");
        assertTrue(result.get("isError").getAsBoolean());
    }

    private static JsonObject request(JsonObject args) {
        return object("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", object("name", "platform_probe", "arguments", args));
    }
}
