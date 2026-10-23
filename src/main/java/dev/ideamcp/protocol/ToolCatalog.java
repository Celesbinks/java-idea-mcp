package dev.ideamcp.protocol;

import com.google.gson.*;
import java.util.Set;
import static dev.ideamcp.protocol.Json.object;

public final class ToolCatalog {
    public static final Set<String> NAMES = Set.of("list_projects", "read_symbols", "find_definition", "find_references", "list_run_configurations", "restart_run_configuration", "get_run_operation");
    private ToolCatalog() {}
    public static JsonArray tools() {
        JsonArray tools = new JsonArray();
        tools.add(tool("list_projects", "List open projects and their indexing state; use a returned project path to select a project.", object(), new String[]{}));
        JsonObject common = object("project", string("Exact project base path. Optional only when exactly one project is open."));
        JsonObject symbols = common.deepCopy();
        symbols.add("file", string("Project-relative or absolute Java file path. Returns declarations in source order."));
        symbols.add("query", string("Exact short class/method/field name or fully qualified class name. Supply exactly one of file/query."));
        symbols.add("includeLibraries", object("type", "boolean", "default", false));
        paging(symbols);
        tools.add(tool("read_symbols", "Read Java class/interface/enum/record, method, constructor and field symbols with signatures and locations. Indexed name search is exact, case-sensitive.", symbols, new String[]{}));
        JsonObject position = common.deepCopy();
        position.add("file", string("Project-relative or absolute file path inside project content."));
        position.add("line", object("type", "integer", "minimum", 1, "description", "1-based line in current IDEA editor contents."));
        position.add("column", object("type", "integer", "minimum", 1, "description", "1-based UTF-16 column on the symbol/reference, not whitespace."));
        tools.add(tool("find_definition", "Resolve the Java reference at a position, including overloaded methods. Returns all valid polyvariant targets or the declaration itself.", position.deepCopy(), new String[]{"file", "line", "column"}));
        paging(position);
        position.add("includeDeclaration", object("type", "boolean", "default", false));
        tools.add(tool("find_references", "Find semantic usages in project content of the symbol at a declaration or reference position. Resolves the exact overload; ambiguous targets return an error.", position, new String[]{"file", "line", "column"}));
        JsonObject configurations = common.deepCopy();
        configurations.add("name", string("Optional case-insensitive substring of the saved configuration name."));
        tools.add(tool("list_run_configurations", "List existing IDEA run configurations with exact IDs, supported Run/Debug modes and live process/debugger sessions. Does not expose VM arguments or environment variables.", configurations, new String[]{}));
        JsonObject restart = common.deepCopy();
        restart.add("configurationId", string("Exact ID returned by list_run_configurations. Only this configuration's instances in this project are stopped."));
        restart.add("mode", object("type", "string", "enum", new String[]{"preserve", "run", "debug"}, "default", "preserve", "description", "Preserve current Run/Debug mode; if stopped, defaults to Run. Explicit debug starts with IDEA debugger and existing breakpoints."));
        restart.add("stopTimeoutSeconds", object("type", "integer", "minimum", 1, "maximum", 120, "default", 20));
        restart.add("startTimeoutSeconds", object("type", "integer", "minimum", 1, "maximum", 600, "default", 120));
        tools.add(tool("restart_run_configuration", "Stop this configuration's current instances, await termination, then start through IDEA in Run or Debug mode. Starts a stopped configuration too. Executes the configuration's existing before-launch tasks. Returns an operationId immediately; poll get_run_operation. This changes running services; invoke only when the user requests starting/restarting that service.", restart, new String[]{"configurationId"}));
        JsonObject operation = common.deepCopy();
        operation.add("operationId", string("Operation ID returned by restart_run_configuration, in the same project."));
        tools.add(tool("get_run_operation", "Inspect restart progress, process PID/exit code and debugger attachment. A started process does not imply Spring Boot is healthy. On start_timeout keep polling; do not blindly retry the restart.", operation, new String[]{"operationId"}));
        return tools;
    }
    private static JsonObject string(String description) { return object("type", "string", "minLength", 1, "description", description); }
    private static void paging(JsonObject properties) {
        properties.add("limit", object("type", "integer", "minimum", 1, "maximum", 200, "default", 100));
        properties.add("offset", object("type", "integer", "minimum", 0, "maximum", 1000000, "default", 0));
    }
    private static JsonObject tool(String name, String description, JsonObject properties, String[] required) {
        JsonObject schema = object("type", "object", "properties", properties, "required", required, "additionalProperties", false);
        if (name.equals("read_symbols")) schema.add("oneOf", Json.GSON.toJsonTree(new Object[]{object("required", new String[]{"file"}, "not", object("required", new String[]{"query"})), object("required", new String[]{"query"}, "not", object("required", new String[]{"file"}))}));
        boolean mutation = name.equals("restart_run_configuration");
        return object("name", name, "description", description, "inputSchema", schema,
                "annotations", object("readOnlyHint", !mutation, "destructiveHint", mutation, "idempotentHint", !mutation, "openWorldHint", mutation));
    }
}
