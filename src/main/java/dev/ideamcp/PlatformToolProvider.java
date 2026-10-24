package dev.ideamcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.extensions.ExtensionPointName;

/**
 * Optional platform adapters return complete MCP results, never business-data wrappers.
 */
public interface PlatformToolProvider {
    ExtensionPointName<PlatformToolProvider> EP = ExtensionPointName.create("dev.ideamcp.symbols.platformTools");

    JsonArray tools();

    default JsonObject tool(String name) {
        for (var item : tools())
            if (name.equals(item.getAsJsonObject().get("name").getAsString())) return item.getAsJsonObject();
        return null;
    }

    default dev.ideamcp.protocol.RpcHandler.ToolCall prepare(String name) {
        JsonObject descriptor = tool(name);
        return descriptor == null ? null : new dev.ideamcp.protocol.RpcHandler.ToolCall(descriptor, args -> {
            JsonObject current = tool(name);
            if (current == null) throw new IllegalArgumentException("PLATFORM_TOOL_UNAVAILABLE: " + name);
            dev.ideamcp.protocol.SchemaValidator.validate(current.getAsJsonObject("inputSchema"), args);
            return call(name, args);
        });
    }

    JsonObject call(String name, JsonObject arguments) throws Exception;
}
