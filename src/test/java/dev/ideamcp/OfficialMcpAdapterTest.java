package dev.ideamcp;

import com.google.gson.*;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.testFramework.*;
import com.intellij.testFramework.builders.JavaModuleFixtureBuilder;
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase;
import com.intellij.util.concurrency.AppExecutorUtil;
import dev.ideamcp.protocol.RpcHandler;

import java.util.*;
import java.util.concurrent.Callable;

import static dev.ideamcp.protocol.Json.object;

public final class OfficialMcpAdapterTest extends JavaCodeInsightFixtureTestCase {
    private Map<String, com.intellij.mcpserver.settings.McpToolDisallowListSettings.ToolState> oldStates;
    private com.intellij.mcpserver.McpSessionInvocationMode oldMode;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        var root = com.intellij.openapi.application.WriteAction.compute(() -> com.intellij.openapi.vfs.VfsUtil.createDirectories(getProject().getBasePath()));
        if (!com.intellij.openapi.roots.ProjectFileIndex.getInstance(getProject()).isInContent(root))
            PsiTestUtil.addContentRoot(getModule(), root);
        var settings = com.intellij.mcpserver.settings.McpToolDisallowListSettings.getInstance();
        oldStates = settings.getToolStates();
        settings.setToolStates(Map.of()); // Exercise real SDK defaults: enabled=true, routerOnly=true.
        var filter = com.intellij.mcpserver.settings.McpToolFilterSettings.getInstance();
        oldMode = filter.getInvocationMode();
        filter.setInvocationMode(com.intellij.mcpserver.McpSessionInvocationMode.DIRECT);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (oldMode != null)
                com.intellij.mcpserver.settings.McpToolFilterSettings.getInstance().setInvocationMode(oldMode);
            if (oldStates != null)
                com.intellij.mcpserver.settings.McpToolDisallowListSettings.getInstance().setToolStates(oldStates);
        } finally {
            super.tearDown();
        }
    }

    @Override
    protected void tuneFixture(JavaModuleFixtureBuilder b) throws Exception {
        super.tuneFixture(b);
        b.addJdk(System.getProperty("java.home"));
        b.setLanguageLevel(LanguageLevel.JDK_21);
    }

    public void testDiscoversRealToolsAndReadsTheFixtureProject() throws Exception {
        JsonObject created = call("create_new_file", object("pathInProject", "probe.txt", "text", "hello sandbox", "overwrite", false));
        assertFalse(created.toString(), created.get("isError").getAsBoolean());
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        JsonArray catalog = background(() -> new IdeaToolBackend().tools());
        Set<String> names = new HashSet<>();
        for (JsonElement item : catalog) assertTrue(names.add(item.getAsJsonObject().get("name").getAsString()));
        assertTrue(names.toString(), names.containsAll(Set.of("read_symbols", "get_tool_operation", "cancel_tool_operation", "read_file", "search_text", "rename_refactoring", "execute_tool", "build_project")));
        Set<String> baseline;
        try (var stream = getClass().getResourceAsStream("/jetbrains-tools-262.txt")) {
            baseline = new HashSet<>(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().toList());
        }
        Set<String> missing = new HashSet<>(baseline);
        missing.removeAll(names);
        // The local Ultimate extension references an installation-specific startup class;
        // it cannot load in the agent-free test JVM. Do not fake its four Qodana tools.
        assertEquals(Set.of("generate_inspection_kts_examples", "run_inspection_kts", "generate_psi_tree", "generate_inspection_kts_api"), missing);
        assertEquals(73, names.size());
        JsonObject result = call("read_file", object("file_path", "probe.txt", "offset", 1, "limit", 20));
        assertFalse(result.toString(), result.get("isError").getAsBoolean());
        assertTrue(result.toString(), result.toString().contains("hello sandbox"));
        result = call("get_project_modules", object());
        assertFalse(result.toString(), result.get("isError").getAsBoolean());
    }

    public void testFileMutationAndSemanticRenameUseTheRealPlatform() throws Exception {
        var source = com.intellij.openapi.application.WriteAction.compute(() -> com.intellij.openapi.vfs.VfsUtil.createDirectories(getProject().getBasePath() + "/src"));
        PsiTestUtil.addSourceRoot(getModule(), source);
        String path = "src/Probe.java";
        JsonObject result = call("create_new_file", object("pathInProject", path, "text", "class Probe { int count; int read(){return count;} }", "overwrite", false));
        assertFalse(result.toString(), result.get("isError").getAsBoolean());
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        result = call("rename_refactoring", object("pathInProject", path, "symbolName", "count", "newName", "total"));
        assertFalse(result.toString(), result.get("isError").getAsBoolean());
        result = call("read_file", object("file_path", path, "limit", 100));
        assertTrue(result.toString(), result.toString().contains("total"));
        assertFalse(result.toString(), result.toString().contains("count"));
        result = call("reformat_file", object("files", new String[]{path}));
        assertFalse(result.toString(), result.get("isError").getAsBoolean());
    }

    public void testUnknownArgumentsAreRejectedBeforePlatformCall() throws Exception {
        JsonObject result = call("get_project_modules", object("typo", true));
        assertTrue(result.get("isError").getAsBoolean());
        assertTrue(result.toString().contains("unknown argument"));
        result = call("reformat_file", object("files", new int[]{1}));
        assertTrue(result.get("isError").getAsBoolean());
    }

    public void testDisabledAndRouterOnlyToolsCannotBeCalledDirectly() throws Exception {
        var settings = com.intellij.mcpserver.settings.McpToolDisallowListSettings.getInstance();
        var states = new HashMap<>(settings.getToolStates());
        String key = com.intellij.mcpserver.McpToolsProvider.Companion.getEP().getExtensionList().stream().flatMap(p -> p.getTools().stream()).filter(t -> t.getDescriptor().getName().equals("read_file")).findFirst().orElseThrow().getDescriptor().getFullyQualifiedName();
        states.put(key, new com.intellij.mcpserver.settings.McpToolDisallowListSettings.ToolState(false, false));
        settings.setToolStates(states);
        assertFalse(background(() -> new OfficialMcpAdapter().tools()).toString().contains("\"name\":\"read_file\""));
        states.put(key, new com.intellij.mcpserver.settings.McpToolDisallowListSettings.ToolState(true, true));
        settings.setToolStates(states);
        // Official DIRECT sessions include routerOnly defaults; VIA_ROUTER hides them from direct calls.
        assertTrue(background(() -> new OfficialMcpAdapter().tools()).toString().contains("\"name\":\"read_file\""));
        com.intellij.mcpserver.settings.McpToolFilterSettings.getInstance().setInvocationMode(com.intellij.mcpserver.McpSessionInvocationMode.VIA_ROUTER);
        assertFalse(background(() -> new OfficialMcpAdapter().tools()).toString().contains("\"name\":\"read_file\""));
        String rejection = background(() -> {
            try {
                new OfficialMcpAdapter().call("read_file", object("file_path", "fixture", "projectPath", getProject().getBasePath()));
                return "accepted";
            } catch (IllegalArgumentException expected) {
                return expected.getMessage();
            }
        });
        assertTrue(rejection, rejection.contains("PLATFORM_TOOL_UNAVAILABLE"));
    }

    public void testOfficialRouterUsesARealFilteredSessionAndPreservesFailures() throws Exception {
        com.intellij.mcpserver.settings.McpToolFilterSettings.getInstance().setInvocationMode(com.intellij.mcpserver.McpSessionInvocationMode.VIA_ROUTER);
        JsonArray catalog = background(() -> new IdeaToolBackend().tools());
        assertEquals(22, catalog.size());
        JsonObject result = completedPlatformResult(call("execute_tool", object("command", "get_project_modules")));
        assertFalse(result.toString(), result.get("isError").getAsBoolean());
        assertTrue(result.toString(), result.toString().contains("modules"));
        var settings = com.intellij.mcpserver.settings.McpToolDisallowListSettings.getInstance();
        String key = com.intellij.mcpserver.McpToolsProvider.Companion.getEP().getExtensionList().stream().flatMap(p -> p.getTools().stream()).filter(t -> t.getDescriptor().getName().equals("get_project_modules")).findFirst().orElseThrow().getDescriptor().getFullyQualifiedName();
        settings.setToolStates(Map.of(key, new com.intellij.mcpserver.settings.McpToolDisallowListSettings.ToolState(false, false)));
        result = completedPlatformResult(call("execute_tool", object("command", "get_project_modules")));
        assertTrue(result.toString(), result.get("isError").getAsBoolean());
        assertTrue(result.toString(), result.toString().contains("get_project_modules"));
        assertFalse(result.toString().contains("Session handler not available"));
    }

    private JsonObject completedPlatformResult(JsonObject accepted) throws Exception {
        assertFalse(accepted.toString(), accepted.get("isError").getAsBoolean());
        String id = accepted.getAsJsonObject("structuredContent").get("operationId").getAsString();
        var service = com.intellij.openapi.application.ApplicationManager.getApplication().getService(PlatformOperationService.class);
        PlatformTestUtil.waitWithEventsDispatching("official routed tool", () -> !service.query(object("operationId", id), false).get("inProgress").getAsBoolean(), 20);
        JsonObject operation = service.query(object("operationId", id), false);
        assertTrue(operation.toString(), operation.get("result").isJsonObject());
        return operation.getAsJsonObject("result");
    }

    public void testLaunchFieldsAreSanitizedInTextAndStructuredResults() {
        JsonObject data = object("configurations", new Object[]{object("name", "fixture", "programArguments", "fixture-only-argument", "envs", object("FIXTURE_SECRET", "fixture-only-value"), "vmOptions", "fixture-only-vm", "commandLine", "fixture-only-command", "environment", object("FIXTURE_SECRET", "fixture-only-value"))});
        JsonObject r = OfficialMcpAdapter.redactLaunchFields(object("content", new Object[]{object("type", "text", "text", data.toString())}, "structuredContent", data, "isError", false));
        assertTrue(r.toString().contains("fixture"));
        assertFalse(r.toString().contains("fixture-only"));
    }

    public void testReadOnlyProviderGroupsAndStructuredLaunchRedaction() throws Exception {
        for (String name : List.of("get_project_dependencies", "get_all_open_file_paths", "get_repositories", "git_status", "list_database_connections", "xdebug_get_debugger_status", "xdebug_list_breakpoints")) {
            JsonObject result = call(name, object());
            assertFalse(name + ": " + result, result.get("isError").getAsBoolean());
        }
        JsonObject absent = call("list_recent_sql_queries", object("connectionId", "fixture-absent-connection"));
        assertTrue(absent.toString(), absent.get("isError").getAsBoolean());
        assertFalse(absent.toString().contains("missing connectionId"));
        var manager = com.intellij.execution.RunManager.getInstance(getProject());
        var settings = manager.createConfiguration("McpFixture", com.intellij.execution.application.ApplicationConfigurationType.getInstance().getConfigurationFactories()[0]);
        var configuration = (com.intellij.execution.application.ApplicationConfiguration) settings.getConfiguration();
        configuration.setModule(getModule());
        configuration.setMainClassName("Fixture");
        configuration.setProgramParameters("fixture-only-argument");
        configuration.setEnvs(Map.of("FIXTURE_SECRET", "fixture-only-value"));
        manager.addConfiguration(settings);
        try {
            JsonObject result = call("get_run_configurations", object());
            assertFalse(result.toString(), result.get("isError").getAsBoolean());
            assertTrue(result.toString().contains("McpFixture"));
            assertFalse(result.toString().contains("fixture-only"));
            result = completedPlatformResult(call("execute_tool", object("command", "get_run_configurations")));
            assertFalse(result.toString(), result.get("isError").getAsBoolean());
            assertTrue(result.toString().contains("McpFixture"));
            assertFalse(result.toString().contains("fixture-only"));
        } finally {
            manager.removeConfiguration(settings);
        }
    }

    public void testPatchSearchAndProjectBoundary() throws Exception {
        JsonObject result = call("create_new_file", object("pathInProject", "fixture.txt", "text", "before fixture\n", "overwrite", false));
        assertFalse(result.toString(), result.get("isError").getAsBoolean());
        result = call("apply_patch", object("input", "*** Begin Patch\n*** Update File: fixture.txt\n@@\n-before fixture\n+after fixture\n*** End Patch"));
        assertFalse(result.toString(), result.get("isError").getAsBoolean());
        for (String name : List.of("search_text", "search_regex", "search_file")) {
            result = call(name, object("q", name.equals("search_file") ? "fixture.txt" : name.equals("search_regex") ? "after\\s+fixture" : "after fixture", "limit", 10));
            assertFalse(name + ": " + result, result.get("isError").getAsBoolean());
            assertTrue(name + ": " + result, result.toString().contains("fixture.txt"));
        }
        result = call("list_directory_tree", object("directoryPath", ".", "maxDepth", 2));
        assertFalse(result.toString(), result.get("isError").getAsBoolean());
        result = call("read_file", object("file_path", "../outside-fixture.txt"));
        assertTrue(result.get("isError").getAsBoolean());
    }

    private JsonObject call(String name, JsonObject args) throws Exception {
        args.addProperty("projectPath", getProject().getBasePath());
        JsonObject response = background(() -> new RpcHandler(new IdeaToolBackend()).handle(object("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", object("name", name, "arguments", args))));
        assertFalse(response.toString(), response.has("error"));
        return response.getAsJsonObject("result");
    }

    public void testNamedDescriptorsMatchTheCompleteFilteredCatalog() throws Exception {
        background(() -> {
            var adapter = new OfficialMcpAdapter();
            var backend = new IdeaToolBackend();
            for (JsonElement item : adapter.tools()) {
                String name = item.getAsJsonObject().get("name").getAsString();
                assertEquals(item, adapter.tool(name));
                assertEquals(item, backend.tool(name));
            }
            assertNull(adapter.tool("fixture_missing_tool"));
            assertNull(backend.tool("fixture_missing_tool"));
            assertNull(adapter.tool("read_symbols"));
            return null;
        });
    }

    public void testLookupCannotAuthorizeACallAfterSettingsChange() throws Exception {
        var prepared = background(() -> new IdeaToolBackend().prepare("read_file"));
        assertNotNull(prepared);
        String key = com.intellij.mcpserver.McpToolsProvider.Companion.getEP().getExtensionList().stream().flatMap(p -> p.getTools().stream())
                .filter(t -> t.getDescriptor().getName().equals("read_file")).findFirst().orElseThrow().getDescriptor().getFullyQualifiedName();
        var settings = com.intellij.mcpserver.settings.McpToolDisallowListSettings.getInstance();
        settings.setToolStates(Map.of(key, new com.intellij.mcpserver.settings.McpToolDisallowListSettings.ToolState(false, false)));
        assertNull(background(() -> new OfficialMcpAdapter().tool("read_file")));
        assertTrue(background(() -> {
            try { prepared.call(object("projectPath", getProject().getBasePath(), "file_path", "probe.txt")); return false; }
            catch (IllegalArgumentException expected) { return expected.getMessage().contains("PLATFORM_TOOL_UNAVAILABLE"); }
        }));
        settings.setToolStates(Map.of());
        com.intellij.mcpserver.settings.McpToolFilterSettings.getInstance().setInvocationMode(com.intellij.mcpserver.McpSessionInvocationMode.VIA_ROUTER);
        assertNull(background(() -> new OfficialMcpAdapter().tool("read_file")));
        assertNotNull(background(() -> new OfficialMcpAdapter().tool("execute_tool")));
    }

    public void testNamedDispatchAvoidsFullProviderConversionAndKeepsValidation() throws Exception {
        var full = new java.util.concurrent.atomic.AtomicInteger();
        var single = new java.util.concurrent.atomic.AtomicInteger();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        JsonObject descriptor = object("name", "fixture_named_probe", "inputSchema", object("type", "object", "properties", object("value", object("type", "string")), "required", new String[]{"value"}, "additionalProperties", false));
        PlatformToolProvider provider = new PlatformToolProvider() {
            @Override public JsonArray tools() { full.incrementAndGet(); JsonArray a = new JsonArray(); a.add(descriptor.deepCopy()); return a; }
            @Override public JsonObject tool(String name) { single.incrementAndGet(); return name.equals("fixture_named_probe") ? descriptor.deepCopy() : null; }
            @Override public JsonObject call(String name, JsonObject args) { calls.incrementAndGet(); return object("content", new Object[]{object("type", "text", "text", args.get("value").getAsString())}, "isError", false); }
        };
        var mask = com.intellij.openapi.util.Disposer.newDisposable();
        com.intellij.openapi.util.Disposer.register(getTestRootDisposable(), mask);
        ExtensionTestUtil.maskExtensions(PlatformToolProvider.EP, List.of(provider), mask);
        background(() -> {
            var handler = new RpcHandler(new IdeaToolBackend());
            var result = handler.handle(object("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", object("name", "fixture_named_probe", "arguments", object("value", "complete fixture"))));
            assertFalse(result.toString(), result.getAsJsonObject("result").get("isError").getAsBoolean());
            assertEquals(0, full.get()); assertEquals(2, single.get()); assertEquals(1, calls.get());
            result = handler.handle(object("jsonrpc", "2.0", "id", 2, "method", "tools/call", "params", object("name", "fixture_named_probe", "arguments", object("value", "fixture", "typo", true))));
            assertTrue(result.toString(), result.getAsJsonObject("result").get("isError").getAsBoolean());
            assertEquals(1, calls.get()); assertEquals(0, full.get());
            var catalog = handler.handle(object("jsonrpc", "2.0", "id", 3, "method", "tools/list", "params", object()));
            assertEquals(22, catalog.getAsJsonObject("result").getAsJsonArray("tools").size()); assertEquals(1, full.get());
            return null;
        });
        var prepared = background(() -> new IdeaToolBackend().prepare("fixture_named_probe"));
        com.intellij.openapi.util.Disposer.dispose(mask);
        assertTrue(background(() -> {
            try { prepared.call(object("value", "fixture")); return false; }
            catch (IllegalArgumentException expected) { return expected.getMessage().contains("PLATFORM_TOOL_UNAVAILABLE"); }
        }));
        assertEquals(1, calls.get());
    }

    public void testSameLayerNamedLookupBenchmark() throws Exception {
        if (System.getProperty("idea.mcp.test.rg", "").isBlank()) return;
        var repository = java.nio.file.Path.of(System.getProperty("user.dir"));
        var fixture = java.nio.file.Path.of(getProject().getBasePath());
        try (var files = java.nio.file.Files.walk(repository.resolve("src"))) {
            for (var source : files.filter(java.nio.file.Files::isRegularFile).toList()) {
                var target = fixture.resolve(repository.relativize(source));
                java.nio.file.Files.createDirectories(target.getParent()); java.nio.file.Files.copy(source, target);
            }
        }
        com.intellij.openapi.application.WriteAction.run(() -> com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(fixture).refresh(false, true));
        PsiTestUtil.addSourceRoot(getModule(), com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(fixture.resolve("src/main/java")));
        PsiTestUtil.addSourceRoot(getModule(), com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(fixture.resolve("src/test/java")));
        // Include a real third-party dependency instead of benchmarking an empty list.
        PsiTestUtil.addLibrary(getModule(), "benchmark-tomlj", repository.resolve("build/local/runtime-dependencies").toString(), "tomlj-1.1.1.jar");
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        JsonArray cases = new JsonArray();
        cases.add(object("name", "get_project_modules", "args", object()));
        cases.add(object("name", "get_project_dependencies", "args", object()));
        cases.add(object("name", "read_file", "args", object("file_path", "src/main/java/dev/ideamcp/protocol/Arguments.java", "offset", 1, "limit", 5000)));
        cases.add(object("name", "list_directory_tree", "args", object("directoryPath", "src", "maxDepth", 100, "timeout", 10000)));
        cases.add(object("name", "get_symbol_info", "args", object("filePath", "src/main/java/dev/ideamcp/protocol/Arguments.java", "line", 5, "column", 20)));
        cases.add(object("name", "search_symbol", "args", object("q", "TokenStore", "paths", new String[]{"src/main/java/**", "src/test/java/**"}, "include_external", false, "limit", 1000)));
        RpcHandler.Backend legacy = new RpcHandler.Backend() {
            final OfficialMcpAdapter adapter = new OfficialMcpAdapter();
            @Override public JsonArray tools() { return new IdeaToolBackend().tools(); }
            @Override public JsonObject call(String name, JsonObject args) { throw new UnsupportedOperationException(); }
            @Override public JsonObject callTool(String name, JsonObject args) throws Exception {
                assertTrue(adapter.tools().asList().stream().anyMatch(t -> t.getAsJsonObject().get("name").getAsString().equals(name)));
                return adapter.call(name, args);
            }
        };
        JsonArray rows = new JsonArray(); String token = "fixture_named_lookup_AAAAAAAAAAAAAAAA";
        try (var optimized = new dev.ideamcp.protocol.McpHttpServer(0, token, new RpcHandler(new IdeaToolBackend()));
             var original = new dev.ideamcp.protocol.McpHttpServer(0, token, new RpcHandler(legacy))) {
            optimized.start(); original.start();
            background(() -> {
                Map<String, JsonObject> expected = new HashMap<>();
                try (var client = java.net.http.HttpClient.newHttpClient()) {
                    for (int round = -2; round < 20; round++) for (int offset = 0; offset < cases.size(); offset++) {
                        var c = cases.get(Math.floorMod(round + offset, cases.size())).getAsJsonObject();
                        String name = c.get("name").getAsString(); var args = c.getAsJsonObject("args").deepCopy(); args.addProperty("projectPath", getProject().getBasePath());
                        for (String provider : round % 2 == 0 ? List.of("optimized", "original") : List.of("original", "optimized")) {
                            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(provider.equals("optimized") ? optimized.url() : original.url())).timeout(java.time.Duration.ofSeconds(20))
                                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(object("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", object("name", name, "arguments", args)).toString())).build();
                            long start = System.nanoTime();
                            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                            double ms = (System.nanoTime() - start) / 1_000_000.0;
                            assertEquals(200, response.statusCode()); var envelope = JsonParser.parseString(response.body()).getAsJsonObject(); assertFalse(envelope.toString(), envelope.has("error"));
                            var result = envelope.getAsJsonObject("result"); assertFalse(result.toString(), result.get("isError").getAsBoolean());
                            if (name.equals("get_project_dependencies"))
                                assertFalse(result.toString(), result.getAsJsonObject("structuredContent").getAsJsonArray("dependencies").isEmpty());
                            assertFalse(result.toString(), result.has("structuredContent") && result.getAsJsonObject("structuredContent").has("more") && result.getAsJsonObject("structuredContent").get("more").getAsBoolean());
                            if (expected.containsKey(name)) assertEquals(name, expected.get(name), result); else expected.put(name, result);
                            if (round >= 0) rows.add(object("tool", name, "provider", provider, "round", round, "elapsedMs", ms, "responseCharacters", response.body().length()));
                        }
                    }
                    // A real project-overview task: four independent complete queries,
                    // measured through the same bounded HTTP server under equal load.
                    for (int round = -2; round < 20; round++) {
                        for (String provider : round % 2 == 0 ? List.of("optimized", "original") : List.of("original", "optimized")) {
                            List<java.net.http.HttpRequest> requests = new ArrayList<>();
                            for (int i = 0; i < 4; i++) {
                                var c = cases.get(i).getAsJsonObject(); var args = c.getAsJsonObject("args").deepCopy(); args.addProperty("projectPath", getProject().getBasePath());
                                requests.add(java.net.http.HttpRequest.newBuilder(java.net.URI.create(provider.equals("optimized") ? optimized.url() : original.url())).timeout(java.time.Duration.ofSeconds(20))
                                        .header("Authorization", "Bearer " + token).header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(object("jsonrpc", "2.0", "id", i + 1, "method", "tools/call", "params", object("name", c.get("name").getAsString(), "arguments", args)).toString())).build());
                            }
                            long start = System.nanoTime();
                            var futures = requests.stream().map(request -> client.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.ofString())).toList();
                            java.util.concurrent.CompletableFuture.allOf(futures.toArray(java.util.concurrent.CompletableFuture[]::new)).get(20, java.util.concurrent.TimeUnit.SECONDS);
                            double ms = (System.nanoTime() - start) / 1_000_000.0;
                            int characters = 0;
                            for (int i = 0; i < futures.size(); i++) {
                                var response = futures.get(i).join(); assertEquals(200, response.statusCode());
                                var envelope = JsonParser.parseString(response.body()).getAsJsonObject(); assertFalse(envelope.toString(), envelope.has("error"));
                                assertEquals(expected.get(cases.get(i).getAsJsonObject().get("name").getAsString()), envelope.getAsJsonObject("result"));
                                characters += response.body().length();
                            }
                            if (round >= 0) rows.add(object("tool", "project_overview", "provider", provider, "round", round, "elapsedMs", ms, "responseCharacters", characters));
                        }
                    }
                    // Investigate the directory-tree tail separately with more samples;
                    // retain the initial 20-round results instead of overwriting them.
                    var treeCase = cases.get(3).getAsJsonObject();
                    var treeArgs = treeCase.getAsJsonObject("args").deepCopy(); treeArgs.addProperty("projectPath", getProject().getBasePath());
                    for (int round = 0; round < 80; round++) {
                        for (String provider : round % 2 == 0 ? List.of("optimized", "original") : List.of("original", "optimized")) {
                            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(provider.equals("optimized") ? optimized.url() : original.url())).timeout(java.time.Duration.ofSeconds(20))
                                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(object("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", object("name", "list_directory_tree", "arguments", treeArgs)).toString())).build();
                            long start = System.nanoTime(); var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()); double ms = (System.nanoTime() - start) / 1_000_000.0;
                            assertEquals(200, response.statusCode()); var envelope = JsonParser.parseString(response.body()).getAsJsonObject(); assertFalse(envelope.toString(), envelope.has("error"));
                            assertEquals(expected.get("list_directory_tree"), envelope.getAsJsonObject("result"));
                            rows.add(object("tool", "list_directory_tree_tail", "provider", provider, "round", round, "elapsedMs", ms, "responseCharacters", response.body().length()));
                        }
                    }
                }
                return null;
            });
        }
        var output = repository.resolve("build/benchmarks/063-tool-lookup"); java.nio.file.Files.createDirectories(output);
        java.nio.file.Files.writeString(output.resolve("sandbox.json"), object("cases", cases, "samples", rows, "warmupRounds", 2, "measuredRounds", 20).toString());
    }

    public void testNamedDispatchPreservesNonemptyDiagnosticsAndUnsavedContents() throws Exception {
        String path = "src/DiagnosticsProbe.java";
        var created = call("create_new_file", object("pathInProject", path, "text", "public class DiagnosticsProbe { public int broken = \"wrong\"; }", "overwrite", false));
        assertFalse(created.toString(), created.get("isError").getAsBoolean());
        var sourceRoot = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(getProject().getBasePath() + "/src");
        PsiTestUtil.addSourceRoot(getModule(), sourceRoot);
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        var args = object("filePath", path, "errorsOnly", false, "timeout", 10000, "projectPath", getProject().getBasePath());
        var expected = background(() -> new OfficialMcpAdapter().call("get_file_problems", args));
        var actual = call("get_file_problems", args.deepCopy());
        assertEquals(expected, actual);
        assertFalse(actual.toString(), actual.getAsJsonObject("structuredContent").getAsJsonArray("errors").isEmpty());
        var file = sourceRoot.findChild("DiagnosticsProbe.java");
        var document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file);
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText("public class DiagnosticsProbe { public int broken = 1; }"));
        com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        expected = background(() -> new OfficialMcpAdapter().call("get_file_problems", args));
        actual = call("get_file_problems", args.deepCopy());
        assertEquals(expected, actual);
        assertTrue(actual.toString(), actual.getAsJsonObject("structuredContent").getAsJsonArray("errors").isEmpty());
        assertTrue(com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().isDocumentUnsaved(document));
    }

    public void testExternalCodexDiscoversAndCallsTheIsolatedServer() throws Exception {
        String executable = System.getProperty("idea.mcp.test.codex", "");
        if (executable.isBlank()) return; // Optional external-client integration: pass -PcodexExecutable.
        JsonObject created = call("create_new_file", object("pathInProject", "enumeration/Probe.java", "text", "class Probe {}", "overwrite", false));
        assertFalse(created.toString(), created.get("isError").getAsBoolean());
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        String token = "fixture_token_AAAAAAAAAAAAAAAAAAAAAA";
        for (int i = 0; i < 256; i++) java.nio.file.Files.writeString(java.nio.file.Path.of(getProject().getBasePath(), "enumeration", "padding" + i + ".txt"), "fixture");
        var treeInput = object("projectPath", getProject().getBasePath(), "directoryPath", "enumeration", "maxDepth", 100, "timeout", 10000);
        var expectedTree = background(() -> new OfficialMcpAdapter(true, true, kotlinx.coroutines.Dispatchers.getDefault(), false).call("list_directory_tree", treeInput));
        for (int i = 0; i < 3; i++) background(() -> DirectoryTree.tryCall(getProject(), treeInput, System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20)));
        assertEquals(expectedTree, background(() -> DirectoryTree.tryCall(getProject(), treeInput, System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20))));
        try (var server = new dev.ideamcp.protocol.McpHttpServer(0, token, new RpcHandler(new IdeaToolBackend()))) {
            server.start();
            java.nio.file.Path home = java.nio.file.Files.createTempDirectory("mcp-codex-fixture-");
            ClientConfiguration.update(new ClientConfiguration.Target("fixture", home.resolve("config.toml"), ClientConfiguration.Format.CODEX_TOML), new ClientConfiguration.Connection(server.url(), token), null);
            try {
                background(() -> {
                    var builder = new ProcessBuilder(executable, "app-server", "--stdio").directory(java.nio.file.Path.of(getProject().getBasePath()).toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
                    builder.environment().put("CODEX_HOME", home.toString());
                    Process child = builder.start();
                    var watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
                    watchdog.schedule(child::destroyForcibly, 45, java.util.concurrent.TimeUnit.SECONDS);
                    try (var input = new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)); var output = new java.io.PrintWriter(new java.io.OutputStreamWriter(child.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8), true)) {
                        exchange(input, output, 1, "initialize", object("clientInfo", object("name", "mcp-fixture", "version", "0.6.8"), "capabilities", object("experimentalApi", true)));
                        output.println(object("method", "initialized"));
                        JsonObject inventory = exchange(input, output, 2, "mcpServerStatus/list", object("serverName", "idea-java", "limit", 10));
                        JsonObject status = inventory.getAsJsonArray("data").get(0).getAsJsonObject();
                        assertEquals(73, status.getAsJsonObject("tools").size());
                        assertEquals("0.6.8", status.getAsJsonObject("serverInfo").get("version").getAsString());
                        JsonObject started = exchange(input, output, 3, "thread/start", object("cwd", getProject().getBasePath(), "ephemeral", true));
                        String threadId = started.getAsJsonObject("thread").get("id").getAsString();
                        JsonObject result = exchange(input, output, 4, "mcpServer/tool/call", object("server", "idea-java", "threadId", threadId, "tool", "get_project_modules", "arguments", object("projectPath", getProject().getBasePath())));
                        assertFalse(result.toString(), result.toString().contains("\"isError\":true"));
                        assertTrue(result.toString(), result.toString().contains("modules"));
                        result=exchange(input,output,5,"mcpServer/tool/call",object("server","idea-java","threadId",threadId,"tool","list_projects","arguments",object()));
                        assertFalse(result.toString(),result.get("isError").getAsBoolean());assertTrue(result.toString().contains("projects"));
                        result=exchange(input,output,6,"mcpServer/tool/call",object("server","idea-java","threadId",threadId,"tool","execute_tool","arguments",object("projectPath",getProject().getBasePath(),"command","get_project_modules")));
                        String operationId=result.getAsJsonObject("structuredContent").get("operationId").getAsString();
                        JsonObject operation=null;long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);int requestId=7;
                        do{
                            result=exchange(input,output,requestId++,"mcpServer/tool/call",object("server","idea-java","threadId",threadId,"tool","get_tool_operation","arguments",object("operationId",operationId)));
                            operation=result.getAsJsonObject("structuredContent");if(!operation.get("inProgress").getAsBoolean())break;Thread.sleep(20);
                        }while(System.nanoTime()<deadline);
                        assertEquals(operation.toString(),"completed",operation.get("phase").getAsString());assertFalse(operation.toString(),operation.getAsJsonObject("result").get("isError").getAsBoolean());assertTrue(operation.toString().contains("modules"));
                        result = exchange(input, output, requestId++, "mcpServer/tool/call", object("server", "idea-java", "threadId", threadId, "tool", "search_file", "arguments", object("projectPath", getProject().getBasePath(), "q", "**/*.java", "paths", new String[]{"enumeration/**"}, "includeExcluded", false, "limit", 1000)));
                        assertFalse(result.toString(), result.get("isError").getAsBoolean());
                        JsonObject files = result.getAsJsonObject("structuredContent");
                        assertEquals(1, files.getAsJsonArray("items").size());
                        assertEquals("enumeration/Probe.java", files.getAsJsonArray("items").get(0).getAsJsonObject().get("filePath").getAsString().replace('\\', '/'));
                        result = exchange(input, output, requestId++, "mcpServer/tool/call", object("server", "idea-java", "threadId", threadId, "tool", "list_directory_tree", "arguments", treeInput));
                        assertFalse(result.toString(), result.get("isError").getAsBoolean());
                        assertEquals(expectedTree.get("content"), result.get("content"));
                        assertEquals(expectedTree.get("structuredContent"), result.get("structuredContent"));
                        assertFalse(files.toString(), files.has("more") && files.get("more").getAsBoolean());
                        return null;
                    } finally {
                        watchdog.shutdownNow();
                        var descendants = child.descendants().toList();
                        child.destroy();
                        for (var owned : descendants) owned.destroy();
                        if (!child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
                            child.destroyForcibly().waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                        for (var owned : descendants) if (owned.isAlive()) owned.destroyForcibly();
                    }
                });
            } finally {
                try (var paths = java.nio.file.Files.walk(home)) {
                    for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        try {
                            java.nio.file.Files.deleteIfExists(path);
                        } catch (java.nio.file.FileSystemException busy) {
                            path.toFile().deleteOnExit();
                        }
                    }
                }
            }
        }
    }

    private static JsonObject exchange(java.io.BufferedReader input, java.io.PrintWriter output, int id, String method, JsonObject params) throws Exception {
        output.println(object("id", id, "method", method, "params", params));
        String line;
        while ((line = input.readLine()) != null) {
            JsonObject response = JsonParser.parseString(line).getAsJsonObject();
            if (response.has("id") && response.get("id").isJsonPrimitive() && response.get("id").getAsString().equals(Integer.toString(id))) {
                assertFalse(method + ": " + response, response.has("error"));
                return response.getAsJsonObject("result");
            }
        }
        throw new java.io.IOException("Codex integration process closed before " + method);
    }

    private <T> T background(Callable<T> action) throws Exception {
        return PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(action), 60000);
    }
}
