package dev.ideamcp.protocol;

import com.google.gson.*;
import java.util.Set;
import static dev.ideamcp.protocol.Json.object;

public final class ToolCatalog {
    public static final Set<String> NAMES = Set.of("list_projects", "read_symbols", "find_definition", "find_references", "list_run_configurations", "restart_run_configuration", "get_run_operation", "read_run_logs", "get_diagnostics", "find_callers", "find_callees", "list_debug_sessions", "read_debug_threads", "read_debug_stack", "read_debug_variables", "control_debugger", "list_breakpoints", "set_breakpoint", "remove_breakpoint");
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
        tools.add(tool("find_references", "Find semantic usages in project content of the symbol at a declaration or reference position. Resolves the exact overload; ambiguous targets return an error. Deduplicates and sorts by file URL and numeric source offsets before pagination, returning total. Pages are stable while project contents and indices are unchanged.", position, new String[]{"file", "line", "column"}));
        JsonObject calls = position.deepCopy();
        calls.remove("includeDeclaration");
        tools.add(tool("find_callers", "Find one-hop incoming Java method/constructor calls and method references in project content, resolving the exact overload. Returns individual call sites with caller/callee symbols, total and stable source-order pagination. Initializer callers may be null. Static resolution does not expand runtime implementations.", calls.deepCopy(), new String[]{"file", "line", "column"}));
        tools.add(tool("find_callees", "Find one-hop outgoing calls in the selected Java method/constructor body. Includes explicit constructor calls and method references; lambda/reference edges are marked deferred. Excludes nested class bodies and implicit calls; reports unresolvedCount. No runtime dispatch expansion.", calls, new String[]{"file", "line", "column"}));
        JsonObject diagnostics = common.deepCopy();
        diagnostics.add("file", string("Project-content Java file to analyze, including committed unsaved editor contents."));
        diagnostics.add("severity", object("type", "string", "enum", new String[]{"all", "error", "warning"}, "default", "all"));
        paging(diagnostics);
        tools.add(tool("get_diagnostics", "Run IDEA error/warning analysis on one Java file using its inspection profile. Returns messages, UTF-16 positions, total and documentStamp. No compiler/build, quick fixes or external build diagnostics. Retry SOURCE_CHANGED, INDEXING, DOCUMENTS_UNCOMMITTED or DIAGNOSTICS_BUSY; restart pagination when source/profile/index changes.", diagnostics, new String[]{"file"}));
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
        JsonObject logs = common.deepCopy();
        logs.add("configurationId", string("Exact configuration ID from list_run_configurations, scoped to this project."));
        logs.add("sessionId", string("Exact logSessionId from a live session or sessionId from logSessions. Required when multiple retained instances match; always use it when polling."));
        logs.add("cursor", string("Non-negative decimal UTF-16 stream offset, using nextCursor from this same session. Omit to read the recent tail; use 0 for earliest retained output."));
        logs.add("maxChars", object("type", "integer", "minimum", 2, "maximum", 20000, "default", 8000));
        tools.add(tool("read_run_logs", "Read bounded stdout/stderr output for an exact Run/Debug instance. Captures only output emitted since plugin tracking began; no earlier console history or system/command-line output. Supports nextCursor polling and reports truncation when old output was evicted. Application logs can contain secrets. Does not start, stop or restart anything.", logs, new String[]{"configurationId"}));
        tools.add(tool("list_debug_sessions", "List local Java debug sessions for this project, including exact sessionId, running/suspended/command_pending state and pauseId. Only Application, Spring Boot, Maven and Gradle sessions are supported. Does not attach or start a debugger.", common.deepCopy(), new String[]{}));
        JsonObject debug = common.deepCopy();
        debug.add("sessionId", string("Exact live sessionId from list_debug_sessions in this project."));
        debug.add("pauseId", string("Current suspension token from list_debug_sessions. Invalid after resume, stepping, stop or another pause; refresh before reading another snapshot."));
        paging(debug);
        tools.add(tool("read_debug_threads", "Read Java thread IDs, names and suspension state at an existing debugger pause, without suspending additional threads. Requires the exact current pauseId.", debug.deepCopy(), new String[]{"sessionId", "pauseId"}));
        debug.add("threadId", string("Decimal Java thread ID returned by read_debug_threads for this session."));
        tools.add(tool("read_debug_stack", "Read a suspended Java thread's stack frames and source lines. frameIndex is absolute and valid only for the same pauseId. Frames can lack source/debug information.", debug.deepCopy(), new String[]{"sessionId", "pauseId", "threadId"}));
        debug.add("frameIndex", object("type", "integer", "minimum", 0, "maximum", 1000000, "default", 0));
        debug.add("maxStringLength", object("type", "integer", "minimum", 1, "maximum", 1000, "default", 256));
        tools.add(tool("read_debug_variables", "Read locals/arguments and this from a suspended Java frame using JDI. Primitives and bounded string contents are returned; objects/arrays are references, without expansion. Never evaluates expressions, getters or toString. Missing local debug information is reported with argument fallback. Values may contain secrets; do not publish business snapshots.", debug, new String[]{"sessionId", "pauseId", "threadId"}));
        JsonObject control = common.deepCopy();
        control.add("sessionId", string("Exact local Java debug sessionId from list_debug_sessions."));
        control.add("pauseId", string("Required for resume/step actions; use the current pauseId. Omit for pause."));
        control.add("action", object("type", "string", "enum", new String[]{"pause", "resume", "step_into", "step_over", "step_out"}));
        tools.add(tool("control_debugger", "Change only the specified debug session: pause, resume, or step. Invoke only when the user explicitly requests that debugging action. Returns command acceptance, not completion; poll list_debug_sessions for the new state/pauseId. Stepping/resuming can execute application code and change business state. Never retry blindly after timeout.", control, new String[]{"sessionId", "action"}));
        tools.add(tool("list_breakpoints", "List project-content Java line breakpoints with stable in-memory breakpointIds. Breakpoints are shared project-wide with IDEA and all its Java debug sessions. Does not expose condition/log expression text.", common.deepCopy(), new String[]{}));
        JsonObject breakpoint = common.deepCopy();
        breakpoint.add("file", string("Project-content Java file, relative or absolute."));
        breakpoint.add("line", object("type", "integer", "minimum", 1));
        breakpoint.add("enabled", object("type", "boolean", "default", true));
        tools.add(tool("set_breakpoint", "Add or enable/disable a project-wide Java line breakpoint at an executable source line. Existing conditions, log expressions and suspend policies are preserved. Invoke only when the user requests this breakpoint change. New breakpoints persist in IDEA and can suspend matching business sessions; no expression/condition evaluation is provided.", breakpoint, new String[]{"file", "line"}));
        JsonObject remove = common.deepCopy();remove.add("breakpointId", string("Exact breakpointId from list_breakpoints in the same project."));
        tools.add(tool("remove_breakpoint", "Remove exactly one project-wide Java line breakpoint identified by breakpointId. Invoke only when the user requests removing that breakpoint. Does not remove other breakpoints or resume a suspended process.", remove, new String[]{"breakpointId"}));
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
        if (name.equals("control_debugger")) schema.add("oneOf", Json.GSON.toJsonTree(new Object[]{
                object("properties",object("action",object("const","pause")),"not",object("required",new String[]{"pauseId"})),
                object("properties",object("action",object("enum",new String[]{"resume","step_into","step_over","step_out"})),"required",new String[]{"pauseId"})}));
        boolean mutation = Set.of("restart_run_configuration", "control_debugger", "set_breakpoint", "remove_breakpoint").contains(name);
        return object("name", name, "description", description, "inputSchema", schema,
                "annotations", object("readOnlyHint", !mutation, "destructiveHint", mutation, "idempotentHint", !mutation, "openWorldHint", mutation));
    }
}
