package dev.ideamcp;

import com.google.gson.*;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.vfs.*;
import com.intellij.testFramework.*;
import com.intellij.testFramework.builders.JavaModuleFixtureBuilder;
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase;
import com.intellij.util.concurrency.AppExecutorUtil;
import dev.ideamcp.protocol.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static dev.ideamcp.protocol.Json.object;

public final class FileEnumerationTest extends JavaCodeInsightFixtureTestCase {
    private Map<String, com.intellij.mcpserver.settings.McpToolDisallowListSettings.ToolState> oldStates;
    private com.intellij.mcpserver.McpSessionInvocationMode oldMode;

    @Override protected void setUp() throws Exception {
        super.setUp();
        VirtualFile root = WriteAction.compute(() -> VfsUtil.createDirectories(getProject().getBasePath()));
        if (!com.intellij.openapi.roots.ProjectFileIndex.getInstance(getProject()).isInContent(root))
            PsiTestUtil.addContentRoot(getModule(), root);
        var settings = com.intellij.mcpserver.settings.McpToolDisallowListSettings.getInstance();
        oldStates = settings.getToolStates();
        settings.setToolStates(Map.of());
        var filter = com.intellij.mcpserver.settings.McpToolFilterSettings.getInstance();
        oldMode = filter.getInvocationMode();
        filter.setInvocationMode(com.intellij.mcpserver.McpSessionInvocationMode.DIRECT);
    }

    @Override protected void tearDown() throws Exception {
        try {
            com.intellij.mcpserver.settings.McpToolDisallowListSettings.getInstance().setToolStates(oldStates);
            com.intellij.mcpserver.settings.McpToolFilterSettings.getInstance().setInvocationMode(oldMode);
        } finally { super.tearDown(); }
    }

    @Override protected void tuneFixture(JavaModuleFixtureBuilder b) throws Exception {
        super.tuneFixture(b);
        b.addJdk(System.getProperty("java.home"));
    }

    private VirtualFile create(String path) throws Exception {
        return WriteAction.compute(() -> {
            var file = VfsUtil.createDirectoryIfMissing(getProject().getBasePath() + "/" + path.substring(0, path.lastIndexOf('/')))
                    .createChildData(this, path.substring(path.lastIndexOf('/') + 1));
            VfsUtil.saveText(file, "// enumeration fixture\n");
            return file;
        });
    }

    private JsonObject args(String query, String... paths) {
        return object("q", query, "paths", paths, "includeExcluded", false, "limit", 1000, "projectPath", getProject().getBasePath());
    }

    private JsonObject fast(JsonObject args) throws Exception {
        return background(() -> FileEnumeration.tryCall(getProject(), args, System.nanoTime() + TimeUnit.SECONDS.toNanos(20)));
    }

    private JsonObject call(boolean optimized, JsonObject args) throws Exception {
        return background(() -> new OfficialMcpAdapter(optimized).call("search_file", args));
    }

    private static SortedSet<String> paths(JsonObject result) {
        assertFalse(result.toString(), result.get("isError").getAsBoolean());
        JsonObject data = result.getAsJsonObject("structuredContent");
        SortedSet<String> paths = new TreeSet<>();
        for (var item : data.getAsJsonArray("items"))
            assertTrue("Duplicate result", paths.add(item.getAsJsonObject().get("filePath").getAsString().replace('\\', '/')));
        return paths;
    }

    private void equivalent(JsonObject args, boolean shouldOptimize) throws Exception {
        JsonObject quick = fast(args);
        assertEquals(args.toString(), shouldOptimize, quick != null);
        JsonObject original = call(false, args), actual = call(true, args);
        if (original.get("isError").getAsBoolean()) { assertEquals(original, actual); return; }
        assertEquals(args.toString(), paths(original), paths(actual));
        JsonObject oldData = original.getAsJsonObject("structuredContent"), data = actual.getAsJsonObject("structuredContent");
        assertEquals(oldData.get("more"), data.get("more"));
        assertEquals(oldData.get("partialResultReason"), data.get("partialResultReason"));
        if (!shouldOptimize) assertEquals(original, actual); // Including the official truncated prefix/order.
    }

    public void testCompleteResultsOverlappingDirectoriesGlobsAndUnicode() throws Exception {
        create("src/main/java/A.java"); create("src/main/java/nested/B.java");
        create("src/test/java/测试😀.java"); create("src/main/java/Case.JAVA");
        create("src/main/java/readme.txt"); create("other/Outside.java");
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        equivalent(args("**/*.java", "src/main/java/**", "src/test/java/**"), true);
        equivalent(args("*.java", "src/**", "src/main/java/**", "!src/test/**", ""), true);
        equivalent(args("**/{A,B}.java", "src/**"), true);
        equivalent(args("**/测试😀.java", "src/test/java/**"), true);
        equivalent(args("**/NoMatch.java", "src/**"), true);
        assertEquals(Set.of("src/main/java/A.java", "src/main/java/nested/B.java", "src/main/java/Case.JAVA", "src/test/java/测试😀.java"), paths(call(true, args("**/*.java", "src/**"))));
    }

    public void testLimitsAndUnsupportedScopesKeepOriginalResults() throws Exception {
        create("src/main/java/A.java"); create("src/main/java/B.java");
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        for (int limit : new int[]{1, 2}) {
            var input = args("**/*.java", "src/**"); input.addProperty("limit", limit); equivalent(input, false);
        }
        for (String[] filters : List.of(new String[]{"src/*/java/**"}, new String[]{"src/"}, new String[]{"!other/**"}, new String[]{"../**"}, new String[]{"missing/**"}, new String[]{}))
            equivalent(args("**/*.java", filters), false);
        equivalent(args("src/**/*.java", "src/**"), false);
        var excluded = args("**/*.java", "src/**"); excluded.addProperty("includeExcluded", true); equivalent(excluded, false);
        var noPaths = args("**/*.java"); noPaths.remove("paths"); equivalent(noPaths, false);
        for (String query : List.of("", "[")) {
            var input = args(query, "src/**"); assertNull(fast(input)); assertEquals(call(false, input), call(true, input));
        }
        var invalid = args("*.java", "src/**"); invalid.addProperty("limit", 0);
        assertNull(fast(invalid)); assertEquals(call(false, invalid), call(true, invalid));
    }

    public void testContentExclusionsAndVfsChangesAreNotCached() throws Exception {
        VirtualFile file = create("src/main/java/A.java");
        VirtualFile ignored = create("src/generated/Generated.java");
        PsiTestUtil.addExcludedRoot(getModule(), ignored.getParent());
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        var input = args("**/*.java", "src/**"); equivalent(input, true);
        assertEquals(Set.of("src/main/java/A.java"), paths(call(true, input)));
        WriteAction.run(() -> file.rename(this, "Renamed.java"));
        create("src/main/java/New.java"); IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        equivalent(input, true);
        assertEquals(Set.of("src/main/java/Renamed.java", "src/main/java/New.java"), paths(call(true, input)));
        WriteAction.run(() -> file.delete(this)); IndexingTestUtil.waitUntilIndexesAreReady(getProject()); equivalent(input, true);
        input.addProperty("includeExcluded", true); equivalent(input, false);
        assertTrue(paths(call(true, input)).contains("src/generated/Generated.java"));
    }

    public void testBudgetAndCancellationDoNotReturnIncompleteSuccess() throws Exception {
        create("src/main/java/A.java"); IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        var input = args("**/*.java", "src/**");
        assertNull(background(() -> FileEnumeration.tryCall(getProject(), input, System.nanoTime())));
        String outcome = background(() -> {
            var indicator = new com.intellij.openapi.progress.util.ProgressIndicatorBase(); indicator.cancel();
            try {
                com.intellij.openapi.progress.ProgressManager.getInstance().runProcess(() -> {
                    try { FileEnumeration.tryCall(getProject(), input, System.nanoTime() + TimeUnit.SECONDS.toNanos(20)); }
                    catch (RuntimeException e) { throw e; }
                    catch (Exception e) { throw new RuntimeException(e); }
                }, indicator);
                return "returned";
            } catch (com.intellij.openapi.progress.ProcessCanceledException expected) { return "cancelled"; }
        });
        assertEquals("cancelled", outcome);
    }

    public void testOfficialDisabledSearchCannotEnterFastPath() throws Exception {
        create("src/main/java/A.java"); IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        String key = com.intellij.mcpserver.McpToolsProvider.Companion.getEP().getExtensionList().stream().flatMap(p -> p.getTools().stream())
                .filter(t -> t.getDescriptor().getName().equals("search_file")).findFirst().orElseThrow().getDescriptor().getFullyQualifiedName();
        com.intellij.mcpserver.settings.McpToolDisallowListSettings.getInstance().setToolStates(Map.of(key, new com.intellij.mcpserver.settings.McpToolDisallowListSettings.ToolState(false, false)));
        assertTrue(background(() -> {
            try { new OfficialMcpAdapter().call("search_file", args("**/*.java", "src/**")); return false; }
            catch (IllegalArgumentException e) { return e.getMessage().contains("PLATFORM_TOOL_UNAVAILABLE"); }
        }));
    }

    public void testExternalModuleRootsRemainAvailableToNativeJavaQueries() throws Exception {
        var external = myFixture.addFileToProject("External.java", "class External { int value; }");
        create("src/main/java/Inside.java");
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        assertFalse(Path.of(external.getVirtualFile().getPath()).startsWith(Path.of(getProject().getBasePath())));
        var result = background(() -> new JavaCodeTools().call("read_symbols", object("file", external.getVirtualFile().getPath(), "project", getProject().getBasePath())));
        assertEquals(2, result.getAsJsonArray("symbols").size());
        equivalent(args("*.java", "src/**"), true);
        assertEquals(Set.of("src/main/java/Inside.java"), paths(call(true, args("*.java", "src/**"))));
    }

    public void testSameScopeHttpBenchmarkAgainstOriginalProviderAndRg() throws Exception {
        String rg = System.getProperty("idea.mcp.test.rg", "");
        if (rg.isBlank()) return;
        Path repository = Path.of(System.getProperty("user.dir"));
        Path fixture = Path.of(getProject().getBasePath());
        JsonArray corpus = new JsonArray();
        for (String directory : List.of("src/main/java", "src/test/java")) {
            try (var files = Files.walk(repository.resolve(directory))) {
                for (Path source : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    Path relative = repository.relativize(source), target = fixture.resolve(relative);
                    Files.createDirectories(target.getParent()); Files.copy(source, target);
                    corpus.add(object("path", relative.toString().replace('\\', '/'), "sha256", HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)))));
                }
            }
        }
        WriteAction.run(() -> LocalFileSystem.getInstance().refreshAndFindFileByNioFile(fixture).refresh(false, true));
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        JsonObject input = args("**/*.java", "src/main/java/**", "src/test/java/**");
        JsonArray rows = new JsonArray();
        String token = "fixture_enumeration_AAAAAAAAAAAAAAAAAA";
        try (var optimized = new McpHttpServer(0, token, new RpcHandler(new IdeaToolBackend()));
             var original = new McpHttpServer(0, token, new RpcHandler(originalBackend()))) {
            optimized.start(); original.start();
            background(() -> {
                try (var client = java.net.http.HttpClient.newHttpClient()) {
                    SortedSet<String> expected = paths(new OfficialMcpAdapter(false).call("search_file", input));
                    assertEquals(corpus.size(), expected.size());
                    List<List<String>> orders = List.of(List.of("optimized", "official", "rg"), List.of("official", "rg", "optimized"), List.of("rg", "optimized", "official"), List.of("optimized", "rg", "official"), List.of("rg", "official", "optimized"), List.of("official", "optimized", "rg"));
                    for (int round = -2; round < 20; round++) {
                        for (String provider : orders.get(Math.floorMod(round, 6))) {
                            long begin = System.nanoTime();
                            SortedSet<String> actual;
                            int chars;
                            if (provider.equals("rg")) {
                                Process child = new ProcessBuilder(rg, "--files", "src/main/java", "src/test/java", "-g", "*.java").directory(fixture.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                                try {
                                    String output = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                                    assertTrue(child.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, child.exitValue());
                                    actual = new TreeSet<>(output.lines().map(p -> p.replace('\\', '/')).toList()); chars = output.length();
                                } finally { if (child.isAlive()) child.destroyForcibly().waitFor(5, TimeUnit.SECONDS); }
                            } else {
                                String url = provider.equals("optimized") ? optimized.url() : original.url();
                                var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(20))
                                        .header("Authorization", "Bearer " + token).header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(object("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", object("name", "search_file", "arguments", input)).toString())).build();
                                var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                                assertEquals(200, response.statusCode());
                                var envelope = JsonParser.parseString(response.body()).getAsJsonObject(); assertFalse(envelope.toString(), envelope.has("error"));
                                JsonObject result = envelope.getAsJsonObject("result");
                                var data = result.getAsJsonObject("structuredContent");
                                assertFalse(data.toString(), data.has("more") && data.get("more").getAsBoolean());
                                assertFalse(data.toString(), data.has("partialResultReason") && !data.get("partialResultReason").isJsonNull());
                                actual = paths(result); chars = response.body().length();
                            }
                            double elapsed = (System.nanoTime() - begin) / 1_000_000.0;
                            assertEquals(provider, expected, actual);
                            if (round >= 0) rows.add(object("provider", provider, "round", round, "elapsedMs", elapsed, "count", actual.size(), "responseCharacters", chars));
                        }
                    }
                }
                return null;
            });
        }
        Path report = repository.resolve("build/benchmarks/062-file-enumeration"); Files.createDirectories(report);
        Files.writeString(report.resolve("sandbox.json"), object("corpus", corpus, "input", input, "samples", rows, "warmupRounds", 2, "measuredRounds", 20).toString());
    }

    private RpcHandler.Backend originalBackend() {
        return new RpcHandler.Backend() {
            final OfficialMcpAdapter adapter = new OfficialMcpAdapter(false);
            @Override public JsonArray tools() { return new IdeaToolBackend().tools(); }
            @Override public JsonObject call(String name, JsonObject args) { throw new UnsupportedOperationException(); }
            @Override public JsonObject callTool(String name, JsonObject args) throws Exception {
                // Match IdeaToolBackend's discovery/routing overhead before the same provider call.
                assertTrue(adapter.tools().asList().stream().anyMatch(t -> t.getAsJsonObject().get("name").getAsString().equals(name)));
                return adapter.call(name, args);
            }
        };
    }

    private <T> T background(Callable<T> action) throws Exception {
        return PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(action), 60000);
    }
}
