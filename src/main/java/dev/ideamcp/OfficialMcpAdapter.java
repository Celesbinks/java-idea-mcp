package dev.ideamcp;

import com.google.gson.*;
import com.intellij.mcpserver.*;
import com.intellij.mcpserver.elicitation.McpElicitationKind;
import com.intellij.mcpserver.impl.McpServerService;
import com.intellij.mcpserver.impl.McpSessionHandler;
import com.intellij.mcpserver.settings.McpToolFilterSettings;
import com.intellij.mcpserver.toolwindow.TransportType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import dev.ideamcp.protocol.Arguments;
import dev.ideamcp.protocol.SchemaValidator;
import dev.ideamcp.protocol.ToolCatalog;
import dev.ideamcp.protocol.QueryTiming;
import io.modelcontextprotocol.kotlin.sdk.types.Implementation;
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations;
import kotlin.coroutines.CoroutineContext;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.TimeoutKt;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.ideamcp.protocol.Json.object;

/**
 * Adapter to the optional, build-pinned JetBrains MCP runtime.
 */
public final class OfficialMcpAdapter implements PlatformToolProvider {
    private final boolean optimizeFiles;
    private final boolean optimizeGit;
    private final boolean optimizeTree;
    @FunctionalInterface interface TreeQuery { JsonObject call(Project project, JsonObject arguments, long deadline) throws Exception; }
    private final TreeQuery treeQuery;
    private final kotlinx.coroutines.CoroutineDispatcher gitDispatcher;

    public OfficialMcpAdapter() { this(true); }

    OfficialMcpAdapter(boolean optimizeFiles) { this(optimizeFiles, true); }
    OfficialMcpAdapter(boolean optimizeFiles, boolean optimizeGit) { this(optimizeFiles, optimizeGit, Dispatchers.getDefault()); }
    OfficialMcpAdapter(boolean optimizeFiles, boolean optimizeGit, kotlinx.coroutines.CoroutineDispatcher gitDispatcher) {
        this(optimizeFiles, optimizeGit, gitDispatcher, true);
    }
    OfficialMcpAdapter(boolean optimizeFiles, boolean optimizeGit, kotlinx.coroutines.CoroutineDispatcher gitDispatcher, boolean optimizeTree) {
        this(optimizeFiles, optimizeGit, gitDispatcher, optimizeTree, DirectoryTree::tryCall);
    }
    OfficialMcpAdapter(boolean optimizeFiles, boolean optimizeGit, kotlinx.coroutines.CoroutineDispatcher gitDispatcher, boolean optimizeTree, TreeQuery treeQuery) {
        this.optimizeFiles = optimizeFiles; this.optimizeGit = optimizeGit; this.gitDispatcher = Objects.requireNonNull(gitDispatcher); this.optimizeTree = optimizeTree;
        this.treeQuery = Objects.requireNonNull(treeQuery);
    }
    private static final AtomicInteger CALL_IDS = new AtomicInteger();
    private static final Implementation CLIENT = new Implementation("java-symbol-mcp", "0.6.8", null, null, null);
    private static final McpServerService.McpSessionOptions SESSION = new McpServerService.McpSessionOptions(
            McpServerService.AskCommandExecutionMode.RESPECT_GLOBAL_SETTINGS, null, null, null, McpElicitationKind.IDE);
    private static final Set<String> EXPLICIT_EFFECTS = Set.of("execute_tool", "xdebug_evaluate_expression", "xdebug_set_variable", "xdebug_get_frame_values", "xdebug_get_value_by_path", "run_inspection_kts", "execute_sql_query");
    private static final Set<String> SENSITIVE_LAUNCH_FIELDS = Set.of("commandLine", "environment", "programArguments", "envs", "environmentVariables", "vmParameters", "vmOptions", "launchCommandLine");

    private List<McpTool> available() {
        if (ApplicationManager.getApplication().isDispatchThread())
            throw new IllegalStateException("BACKGROUND_THREAD_REQUIRED: platform discovery must run in the background");
        // Router-only is meaningful when routing is enabled. Match the official session's mode.
        McpToolInvocationMode mode = McpToolFilterSettings.getInstance().getInvocationMode() == McpSessionInvocationMode.DIRECT
                ? McpToolInvocationMode.DIRECT : McpToolInvocationMode.DIRECT_WITH_ROUTER_ENABLED;
        return McpServerService.Companion.getInstance().getMcpTools(null, true, CLIENT, SESSION, mode);
    }

    @Override
    public JsonArray tools() {
        JsonArray result = new JsonArray();
        for (McpTool tool : available())
            if (!ToolCatalog.NAMES.contains(tool.getDescriptor().getName())) result.add(descriptor(tool));
        return result;
    }

    @Override
    public JsonObject tool(String name) {
        if (ToolCatalog.NAMES.contains(name)) return null;
        return available().stream().filter(t -> t.getDescriptor().getName().equals(name))
                .findFirst().map(OfficialMcpAdapter::descriptor).orElse(null);
    }

    @Override
    public dev.ideamcp.protocol.RpcHandler.ToolCall prepare(String name) {
        JsonObject descriptor = tool(name);
        // call() always resolves and validates the current official tool again.
        return descriptor == null ? null : new dev.ideamcp.protocol.RpcHandler.ToolCall(descriptor, args -> call(name, args));
    }

    static JsonObject descriptor(McpTool tool) {
        McpToolDescriptor d = tool.getDescriptor();
        McpToolSchema input = d.getInputSchema();
        JsonObject schema = object("type", "object", "properties", JsonParser.parseString(input.getPropertiesSchema().toString()), "required", input.getRequiredProperties().toArray(String[]::new), "additionalProperties", false);
        if (!input.getDefinitions().isEmpty()) {
            String path = input.getDefinitionsPath().replaceFirst("^#/", "");
            JsonObject parent = schema;
            String[] parts = path.split("/");
            for (int i = 0; i < parts.length - 1; i++) {
                JsonObject child = new JsonObject();
                parent.add(parts[i], child);
                parent = child;
            }
            JsonObject definitions = new JsonObject();
            input.getDefinitions().forEach((key, value) -> definitions.add(key, JsonParser.parseString(value.toString())));
            parent.add(parts[parts.length - 1], definitions);
        }
        ToolAnnotations a = d.getAnnotations();
        JsonObject annotations = new JsonObject();
        if (a != null) {
            if (a.getReadOnlyHint() != null) annotations.addProperty("readOnlyHint", a.getReadOnlyHint());
            if (a.getDestructiveHint() != null) annotations.addProperty("destructiveHint", a.getDestructiveHint());
            if (a.getIdempotentHint() != null) annotations.addProperty("idempotentHint", a.getIdempotentHint());
            if (a.getOpenWorldHint() != null) annotations.addProperty("openWorldHint", a.getOpenWorldHint());
        }
        if (EXPLICIT_EFFECTS.contains(d.getName())) {
            annotations.addProperty("readOnlyHint", false);
            annotations.addProperty("destructiveHint", true);
            annotations.addProperty("idempotentHint", false);
        }
        String description = d.getDescription();
        if (PlatformOperationService.LONG_TOOLS.contains(d.getName()))
            description += " This endpoint submits this tool asynchronously and returns operationId. Poll get_tool_operation for the final full MCP result; do not retry the operation on a pending status.";
        if (d.getName().startsWith("xdebug_"))
            description += " Official debugger semantics may evaluate expressions or use IDE renderers. Use read_debug_variables for raw JDI values without evaluation. Debugger mutations require an explicit user request.";
        if (d.getName().equals("get_run_configurations"))
            description += " Sensitive launch arguments and environment fields are omitted by Java Symbol MCP.";
        JsonObject descriptor = object("name", d.getName(), "description", description, "inputSchema", schema, "annotations", annotations);
        if (d.getTitle() != null) descriptor.addProperty("title", d.getTitle());
        // Async submission has an operation envelope rather than the provider's final schema.
        if (d.getOutputSchema() != null && !PlatformOperationService.LONG_TOOLS.contains(d.getName()))
            descriptor.add("outputSchema", JsonParser.parseString(d.getOutputSchema().prettyPrint()));
        return descriptor;
    }

    @Override
    public JsonObject call(String name, JsonObject arguments) throws Exception {
        return call(name, arguments, false);
    }

    JsonObject callOfficialGit(JsonObject arguments) throws Exception { return call("git_status", arguments, true); }

    private JsonObject call(String name, JsonObject arguments, boolean requireOfficialGit) throws Exception {
        McpTool tool;
        try (var phase = QueryTiming.phase("adapter_lookup")) {
            tool = available().stream().filter(t -> t.getDescriptor().getName().equals(name)).findFirst().orElseThrow(() -> new IllegalArgumentException("PLATFORM_TOOL_UNAVAILABLE: " + name));
        }
        boolean officialGit = tool.getDescriptor().getFullyQualifiedName().equals("com.intellij.mcpserver.toolsets.vcs.VcsToolset.git_status");
        if (requireOfficialGit && !officialGit) throw new IllegalArgumentException("PLATFORM_TOOL_UNAVAILABLE: Git provider changed");
        try (var phase = QueryTiming.phase("adapter_schema")) { SchemaValidator.validate(descriptor(tool).getAsJsonObject("inputSchema"), arguments); }
        Project project;
        try (var phase = QueryTiming.phase("adapter_project")) { project = JavaCodeTools.selectProject(Arguments.string(arguments, "projectPath", null)); }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        if (optimizeGit && officialGit && name.equals("git_status")) {
            JsonObject complete;
            try (var phase = QueryTiming.phase("adapter_scope_total")) { complete = GitStatusBatch.tryCall(project, arguments, deadline); }
            if (complete != null) return complete;
        }
        if (optimizeFiles && name.equals("search_file")) {
            JsonObject complete = FileEnumeration.tryCall(project, arguments, deadline);
            if (complete != null) return complete;
        }
        if (optimizeTree && name.equals("list_directory_tree")
                && tool.getDescriptor().getFullyQualifiedName().equals("com.intellij.mcpserver.toolsets.general.FileToolset.list_directory_tree")) {
            JsonObject complete;
            try (var phase = QueryTiming.phase("adapter_tree_total")) { complete = treeQuery.call(project, arguments, deadline); }
            if (complete != null) return complete;
        }
        kotlinx.serialization.json.JsonObject json;
        McpCallInfo info;
        CoroutineContext context;
        QueryTiming.Context timing = QueryTiming.current();
        try (var phase = QueryTiming.phase("adapter_context")) {
        json = (kotlinx.serialization.json.JsonObject) kotlinx.serialization.json.Json.Default.parseToJsonElement(arguments.toString());
        var empty = (kotlinx.serialization.json.JsonObject) kotlinx.serialization.json.Json.Default.parseToJsonElement("{}");
        info = new McpCallInfo(CALL_IDS.incrementAndGet(), new ClientInfo("java-symbol-mcp", "0.6.8"), project, tool.getDescriptor(), json, empty, SESSION, Map.of(), null);
        context = (officialGit ? gitDispatcher : Dispatchers.getDefault()).plus(new McpCallAdditionalDataElement(info));
        if (timing != null) context = context.plus(new TimingElement(timing));
        }
        long timeout = PlatformOperationService.LONG_TOOLS.contains(name) ? 600_000L
                : Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
        McpToolCallResult result;
        try (RouterSession router = name.equals("execute_tool") ? new RouterSession(project) : null) {
            if (router != null) info.setSessionHandler$intellij_mcpserver(router.handler);
            var queued = QueryTiming.phase(timing, "adapter_dispatch");
            var provider = timing == null ? null : new java.util.concurrent.atomic.AtomicReference<QueryTiming.Span>();
            try {
                result = BuildersKt.runBlocking(context, (scope, continuation) -> {
                    queued.close();
                    if (provider != null) provider.set(QueryTiming.phase(timing, "adapter_provider_total"));
                    return TimeoutKt.withTimeout(timeout, (inner, next) -> tool.call(json, next), continuation);
                });
            } finally {
                queued.close();
                if (provider != null && provider.get() != null) provider.get().close();
            }
        } catch (McpExpectedError expected) {
            JsonObject response = object("content", new Object[]{object("type", "text", "text", expected.getMcpErrorText())}, "isError", true);
            if (expected.getMcpErrorStructureContent() != null)
                response.add("structuredContent", JsonParser.parseString(expected.getMcpErrorStructureContent().toString()));
            return name.equals("execute_tool") ? redactRouterResult(response) : name.equals("get_run_configurations") ? redactLaunchFields(response) : response;
        }
        try (var phase = QueryTiming.phase("adapter_result")) {
        JsonArray content = new JsonArray();
        for (McpToolCallResultContent item : result.getContent()) {
            if (item instanceof McpToolCallResultContent.Text text)
                content.add(object("type", "text", "text", text.getText()));
            else throw new IllegalStateException("UNSUPPORTED_PLATFORM_CONTENT");
        }
        JsonObject response = object("content", content, "isError", result.isError());
        if (result.getStructuredContent() != null)
            response.add("structuredContent", JsonParser.parseString(result.getStructuredContent().toString()));
        return name.equals("get_run_configurations") ? redactLaunchFields(response) : name.equals("execute_tool") ? redactRouterResult(response) : response;
        }
    }

    /** Propagates only the request-local metric accumulator, never request data. */
    private static final class TimingElement extends kotlin.coroutines.AbstractCoroutineContextElement implements kotlinx.coroutines.ThreadContextElement<QueryTiming.Context> {
        private static final CoroutineContext.Key<TimingElement> KEY = new CoroutineContext.Key<>() {};
        private final QueryTiming.Context timing;
        TimingElement(QueryTiming.Context timing) { super(KEY); this.timing = timing; }
        @Override public QueryTiming.Context updateThreadContext(CoroutineContext context) { return QueryTiming.attach(timing); }
        @Override public void restoreThreadContext(CoroutineContext context, QueryTiming.Context previous) { QueryTiming.attach(previous); }
    }

    /**
     * No sockets or SDK transport: supplies the real official filtered providers to its router.
     */
    private static final class RouterSession implements AutoCloseable {
        final kotlinx.coroutines.CoroutineScope scope = kotlinx.coroutines.CoroutineScopeKt.CoroutineScope(Dispatchers.getDefault().plus(kotlinx.coroutines.JobKt.Job(null)));
        final io.modelcontextprotocol.kotlin.sdk.server.Server server = new io.modelcontextprotocol.kotlin.sdk.server.Server(CLIENT, new io.modelcontextprotocol.kotlin.sdk.server.ServerOptions(new io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities()), "", s -> kotlin.Unit.INSTANCE);
        final McpSessionHandler handler;

        RouterSession(Project project) {
            try {
                handler = new McpSessionHandler(scope, SESSION, McpServerService.Companion.getInstance(), server, TransportType.STREAMABLE_HTTP, project.getBasePath(), McpElicitationKind.IDE, true);
                handler.updateClientInfo(CLIENT);
                handler.getRouterToolsProvider().updateClientInfo(CLIENT);
            } catch (RuntimeException | Error failure) {
                try {
                    close();
                } catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        @Override
        public void close() throws Exception {
            kotlinx.coroutines.CoroutineScopeKt.cancel(scope, null);
            BuildersKt.runBlocking(Dispatchers.getDefault(), (unused, continuation) -> server.close(continuation));
        }
    }

    static JsonObject redactLaunchFields(JsonObject response) {
        return redactResult(response, true);
    }

    static JsonObject redactRouterResult(JsonObject response) {
        return redactResult(response, false);
    }

    private static JsonObject redactResult(JsonObject response, boolean omitUnstructured) {
        JsonObject copy = response.deepCopy();
        if (copy.has("structuredContent")) redact(copy.get("structuredContent"));
        for (JsonElement item : copy.getAsJsonArray("content")) {
            JsonObject c = item.getAsJsonObject();
            if (!c.has("text")) continue;
            try {
                JsonElement parsed = JsonParser.parseString(c.get("text").getAsString());
                if (!parsed.isJsonObject() && !parsed.isJsonArray()) throw new JsonParseException("Not structured");
                redact(parsed);
                c.addProperty("text", parsed.toString());
            } catch (JsonParseException e) {
                if (omitUnstructured)
                    c.addProperty("text", "Launch details omitted; use structuredContent for sanitized configuration information.");
            }
        }
        return copy;
    }

    private static void redact(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject o = element.getAsJsonObject();
            for (String key : new HashSet<>(o.keySet())) {
                if (SENSITIVE_LAUNCH_FIELDS.contains(key)) o.remove(key);
                else redact(o.get(key));
            }
        } else if (element.isJsonArray()) for (JsonElement item : element.getAsJsonArray()) redact(item);
    }
}
