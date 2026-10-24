package dev.ideamcp;

import com.google.gson.*;
import com.intellij.mcpserver.McpSessionInvocationMode;
import com.intellij.mcpserver.settings.*;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.vfs.*;
import com.intellij.testFramework.*;
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase;
import com.intellij.util.concurrency.AppExecutorUtil;
import dev.ideamcp.protocol.*;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static dev.ideamcp.protocol.Json.object;

public final class DirectoryTreeTest extends JavaCodeInsightFixtureTestCase {
    private McpSessionInvocationMode oldMode;
    private Map<String, McpToolDisallowListSettings.ToolState> oldStates;

    @Override protected void setUp() throws Exception {
        super.setUp();
        Files.createDirectories(root());
        for (int i = 0; i < 64; i++) create("tree/padding" + i + ".txt");
        var filter = McpToolFilterSettings.getInstance(); oldMode = filter.getInvocationMode(); filter.setInvocationMode(McpSessionInvocationMode.DIRECT);
        var settings = McpToolDisallowListSettings.getInstance(); oldStates = settings.getToolStates(); settings.setToolStates(Map.of());
    }
    @Override protected void tearDown() throws Exception {
        try {
            if (oldMode != null) McpToolFilterSettings.getInstance().setInvocationMode(oldMode);
            if (oldStates != null) McpToolDisallowListSettings.getInstance().setToolStates(oldStates);
        } finally { super.tearDown(); }
    }
    private Path root() { return Path.of(getProject().getBasePath()); }
    private Path create(String name) throws Exception {
        Path file = root().resolve(name); Files.createDirectories(file.getParent()); Files.writeString(file, "fixture"); return file;
    }
    private JsonObject args(String name, int depth) { return object("projectPath", root().toString(), "directoryPath", name, "maxDepth", depth, "timeout", 10000); }
    private OfficialMcpAdapter adapter(boolean optimized) { return new OfficialMcpAdapter(true, true, kotlinx.coroutines.Dispatchers.getDefault(), optimized); }
    private JsonObject quick(JsonObject input) throws Exception { return background(() -> DirectoryTree.tryCall(getProject(), input, System.nanoTime() + TimeUnit.SECONDS.toNanos(20))); }
    private JsonObject compare(JsonObject input, boolean optimized) throws Exception {
        if (optimized) WriteAction.run(() -> LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root().resolve(input.get("directoryPath").getAsString())).refresh(false, true));
        if (optimized) for (int i = 0; i < 3; i++) quick(input);
        JsonObject fast = quick(input); assertEquals(input.toString(), optimized, fast != null);
        JsonObject expected = background(() -> adapter(false).call("list_directory_tree", input));
        JsonObject actual = background(() -> adapter(true).call("list_directory_tree", input));
        assertEquals(expected, actual); if (fast != null) assertEquals(expected, fast); return actual;
    }

    public void testCompleteRenderingDepthUnicodeAndDiskChanges() throws Exception {
        create("tree/Zebra.txt"); create("tree/apple.txt"); create("tree/中文😀.txt"); create("tree/.hidden");
        create("tree/folder/child.txt"); Files.createDirectories(root().resolve("tree/empty"));
        for (int depth : new int[]{0, 1, 2, 3, 5, 100}) compare(args("tree", depth), depth > 1);
        var defaults = args("tree", 5); defaults.remove("maxDepth"); defaults.remove("timeout"); compare(defaults, true);
        var before = compare(args("tree", 100), true);
        Files.move(root().resolve("tree/apple.txt"), root().resolve("tree/renamed.txt"));
        Files.delete(root().resolve("tree/Zebra.txt")); create("tree/new.txt");
        var after = compare(args("tree", 100), true); assertFalse(before.equals(after));
        assertTrue(after.toString().contains("renamed.txt")); assertFalse(after.toString().contains("Zebra.txt"));
    }

    public void testExcludedAndUnrefreshedFilesRemainInTheTree() throws Exception {
        create("tree/excluded/old.txt");
        VirtualFile vf = WriteAction.compute(() -> LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root().resolve("tree")));
        PsiTestUtil.addContentRoot(getModule(), vf);
        PsiTestUtil.addExcludedRoot(getModule(), vf.findChild("excluded"));
        create("tree/excluded/unrefreshed.txt"); create("tree/unrefreshed.txt");
        var result = compare(args("tree", 100), true);
        assertTrue(result.toString().contains("excluded")); assertTrue(result.toString().contains("unrefreshed.txt"));
    }

    public void testInvalidScopesAndShortBudgetsFallBack() throws Exception {
        create("tree/leaf.txt");
        for (JsonObject input : List.of(args("missing", 100), args("tree/leaf.txt", 100), args("../", 2), args(root().toString(), 2), args("tree", -1), args("tree", 101))) {
            assertNull(quick(input));
            try { compare(input, false); }
            catch (com.intellij.mcpserver.McpExpectedError expected) { /* Direct official adapter preserves expected errors. */ }
        }
        var shortBudget = args("tree", 100); shortBudget.addProperty("timeout", 1); assertNull(quick(shortBudget));
        assertNull(background(() -> DirectoryTree.tryCall(getProject(), args("tree", 100), System.nanoTime())));
    }

    public void testCancellationIsNotConvertedToSuccess() throws Exception {
        create("tree/a.txt");
        assertEquals("cancelled", background(() -> {
            var indicator = new com.intellij.openapi.progress.util.ProgressIndicatorBase(); indicator.cancel();
            try {
                com.intellij.openapi.progress.ProgressManager.getInstance().runProcess(() -> {
                    try { DirectoryTree.tryCall(getProject(), args("tree", 100), System.nanoTime() + TimeUnit.SECONDS.toNanos(20)); }
                    catch (RuntimeException failure) { throw failure; }
                    catch (Exception failure) { throw new RuntimeException(failure); }
                }, indicator);
                return "returned";
            } catch (com.intellij.openapi.progress.ProcessCanceledException expected) { return "cancelled"; }
        }));
    }

    public void testJunctionFallsBackWithoutChangingOfficialTree() throws Exception {
        create("target/leaf.txt"); Files.createDirectories(root().resolve("tree"));
        Path link = root().resolve("tree/link");
        if (System.getProperty("os.name").startsWith("Windows")) {
            var process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), root().resolve("target").toString()).redirectErrorStream(true).start();
            try { process.getInputStream().readAllBytes(); assertTrue(process.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, process.exitValue()); }
            finally { if (process.isAlive()) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS); }
        } else Files.createSymbolicLink(link, root().resolve("target"));
        try { compare(args("tree", 10), false); }
        finally { Files.delete(link); }
    }

    public void testCaseFoldCollisionPreservesOfficialOrder() throws Exception {
        create("tree/İ.txt"); create("tree/i̇.txt");
        if (!Files.isSameFile(root().resolve("tree/İ.txt"), root().resolve("tree/i̇.txt"))) compare(args("tree", 100), false);
    }

    public void testThinAndEmptyDirectoriesKeepOfficialPath() throws Exception {
        create("thin/a.txt"); Files.createDirectories(root().resolve("empty"));
        compare(args("thin", 100), false); compare(args("empty", 100), false);
        for (int i = 0; i < 31; i++) create("boundary/file" + i + ".txt");
        compare(args("boundary", 100), false);
        create("boundary/last.txt");
        WriteAction.run(() -> LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root().resolve("boundary")).refresh(false, true));
        compare(args("boundary", 100), true);
    }

    public void testStaleThinVfsCannotRestrictFreshDiskResults() throws Exception {
        create("thin/old.txt");
        WriteAction.run(() -> LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root().resolve("thin")).getChildren());
        for (int i = 0; i < 300; i++) create("thin/unrefreshed" + i + ".txt");
        var result = compare(args("thin", 100), false);
        assertTrue(result.toString().contains("unrefreshed299.txt"));
    }

    public void testDisabledAndRouterOnlyToolCannotEnterFastPath() throws Exception {
        create("tree/a.txt");
        var prepared = background(() -> new IdeaToolBackend().prepare("list_directory_tree"));
        String key = com.intellij.mcpserver.McpToolsProvider.Companion.getEP().getExtensionList().stream().flatMap(p -> p.getTools().stream())
                .filter(t -> t.getDescriptor().getName().equals("list_directory_tree")).findFirst().orElseThrow().getDescriptor().getFullyQualifiedName();
        for (var state : List.of(new McpToolDisallowListSettings.ToolState(false, false), new McpToolDisallowListSettings.ToolState(true, true))) {
            McpToolDisallowListSettings.getInstance().setToolStates(Map.of(key, state));
            McpToolFilterSettings.getInstance().setInvocationMode(state.getEnabled() ? McpSessionInvocationMode.VIA_ROUTER : McpSessionInvocationMode.DIRECT);
            assertNull(background(() -> adapter(true).tool("list_directory_tree")));
            assertTrue(background(() -> {
                try { prepared.call(args("tree", 100)); return false; }
                catch (IllegalArgumentException expected) { return expected.getMessage().startsWith("PLATFORM_TOOL_UNAVAILABLE"); }
            }));
        }
    }

    public void testReplacementAndRemovedProviderAreRechecked() throws Exception {
        create("tree/a.txt");
        var prepared = background(() -> new IdeaToolBackend().prepare("list_directory_tree"));
        var ep = com.intellij.mcpserver.McpToolsProvider.Companion.getEP();
        var original = ep.getExtensionList().stream().flatMap(p -> p.getTools().stream())
                .filter(t -> t.getDescriptor().getName().equals("list_directory_tree")).findFirst().orElseThrow().getDescriptor();
        var descriptor = new com.intellij.mcpserver.McpToolDescriptor(original.getName(), original.getTitle(), original.getDescription(), original.getCategory(),
                "fixture.Replacement.list_directory_tree", original.getInputSchema(), original.getOutputSchema(), original.getAnnotations());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var replacement = new com.intellij.mcpserver.McpTool() {
            public com.intellij.mcpserver.McpToolDescriptor getDescriptor() { return descriptor; }
            public Object call(kotlinx.serialization.json.JsonObject input, kotlin.coroutines.Continuation<? super com.intellij.mcpserver.McpToolCallResult> next) {
                calls.incrementAndGet();
                return new com.intellij.mcpserver.McpToolCallResult(new com.intellij.mcpserver.McpToolCallResultContent[]{new com.intellij.mcpserver.McpToolCallResultContent.Text("replacement result")}, null, false);
            }
        };
        var mask = com.intellij.openapi.util.Disposer.newDisposable();
        com.intellij.openapi.util.Disposer.register(getTestRootDisposable(), mask);
        ExtensionTestUtil.maskExtensions(ep, List.of(() -> List.of(replacement)), mask);
        var result = background(() -> prepared.call(args("tree", 100)));
        assertTrue(result.toString().contains("replacement result")); assertEquals(1, calls.get());
        com.intellij.openapi.util.Disposer.dispose(mask);
        var removed = com.intellij.openapi.util.Disposer.newDisposable();
        com.intellij.openapi.util.Disposer.register(getTestRootDisposable(), removed);
        ExtensionTestUtil.maskExtensions(ep, List.of(), removed);
        assertTrue(background(() -> {
            try { prepared.call(args("tree", 100)); return false; }
            catch (IllegalArgumentException expected) { return expected.getMessage().contains("PLATFORM_TOOL_UNAVAILABLE"); }
        }));
        com.intellij.openapi.util.Disposer.dispose(removed);
    }

    public void testBalancedDirectoryPerformance() throws Exception {
        if (System.getProperty("idea.mcp.test.rg", "").isBlank() && !Boolean.getBoolean("idea.mcp.test.directoryBenchmark")) return;
        Path repository = Path.of(System.getProperty("user.dir"));
        try (var files = Files.walk(repository.resolve("src"))) {
            for (Path source : files.filter(Files::isRegularFile).toList()) {
                Path target = root().resolve(repository.relativize(source)); Files.createDirectories(target.getParent()); Files.copy(source, target);
            }
        }
        for (int directory = 0; directory < 32; directory++) for (int file = 0; file < 128; file++) create("large/d" + directory + "/file" + file + ".txt");
        // Both providers share the same warm source-directory VFS state. The large
        // disk-only fixture remains eligible independently of a VFS refresh.
        WriteAction.run(() -> LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root().resolve("src")).getChildren());
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        JsonArray rows = new JsonArray();
        background(() -> {
            String token = "directory_fixture_AAAAAAAAAAAAAAAAAAAA";
            try (var baseline = server(false, token); var candidate = server(true, token); var client = HttpClient.newHttpClient()) {
                baseline.start(); candidate.start();
                for (String directory : List.of("src", "large")) {
                    JsonObject input = args(directory, 100);
                    JsonObject expected = adapter(false).call("list_directory_tree", input);
                    assertFalse(expected.get("isError").getAsBoolean());
                    JsonObject fast = DirectoryTree.tryCall(getProject(), input, System.nanoTime() + TimeUnit.SECONDS.toNanos(20));
                    if (directory.equals("src")) assertNull(fast); else assertEquals(expected, fast);
                    for (int pass = 0; pass < 2; pass++) for (String layer : List.of("direct", "http")) for (int round = -3; round < 80; round++) {
                        for (boolean optimized : (round + pass) % 2 == 0 ? new boolean[]{true, false} : new boolean[]{false, true}) {
                            long start = System.nanoTime(); JsonObject result;
                            if (layer.equals("direct")) result = adapter(optimized).call("list_directory_tree", input);
                            else {
                                var request = HttpRequest.newBuilder(URI.create(optimized ? candidate.url() : baseline.url())).timeout(java.time.Duration.ofSeconds(20))
                                        .header("Authorization", "Bearer " + token).header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                                        .POST(HttpRequest.BodyPublishers.ofString(object("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", object("name", "list_directory_tree", "arguments", input)).toString())).build();
                                var response = client.send(request, HttpResponse.BodyHandlers.ofString()); assertEquals(200, response.statusCode());
                                var envelope = JsonParser.parseString(response.body()).getAsJsonObject(); assertFalse(envelope.has("error")); result = envelope.getAsJsonObject("result");
                            }
                            double ms = (System.nanoTime() - start) / 1_000_000.0;
                            assertEquals(expected, result);
                            if (round >= 0) rows.add(object("directory", directory, "layer", layer, "pass", pass, "round", round, "optimized", optimized, "first", optimized == ((round + pass) % 2 == 0), "ms", ms, "characters", result.toString().length()));
                        }
                    }
                }
            }
            return null;
        });
        Path output = repository.resolve("build/benchmarks/067-readonly"); Files.createDirectories(output);
        Files.writeString(output.resolve("sandbox.json"), object("rows", rows, "warmup", 3, "perProvider", 80).toString());
    }

    private McpHttpServer server(boolean optimized, String token) throws Exception {
        var adapter = adapter(optimized);
        var backend = new RpcHandler.Backend() {
            public JsonObject call(String name, JsonObject args) { throw new UnsupportedOperationException(); }
            public JsonObject tool(String name) { return adapter.tool(name); }
            public JsonObject callTool(String name, JsonObject args) throws Exception { return adapter.call(name, args); }
        };
        return new McpHttpServer(0, token, new RpcHandler(backend), false);
    }
    private <T> T background(Callable<T> task) throws Exception {
        var future = AppExecutorUtil.getAppExecutorService().submit(task);
        // The balanced batch contains 1,328 individually bounded queries.
        try { return PlatformTestUtil.waitForFuture(future, 600_000); }
        finally { if (!future.isDone()) future.cancel(true); }
    }
}
