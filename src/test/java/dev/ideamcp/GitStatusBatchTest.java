package dev.ideamcp;

import com.google.gson.*;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vfs.*;
import com.intellij.testFramework.*;
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase;
import com.intellij.util.concurrency.AppExecutorUtil;
import git4idea.repo.GitRepositoryManager;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static dev.ideamcp.protocol.Json.object;

public final class GitStatusBatchTest extends JavaCodeInsightFixtureTestCase {
    private Map<String, com.intellij.mcpserver.settings.McpToolDisallowListSettings.ToolState> oldStates;
    private com.intellij.mcpserver.McpSessionInvocationMode oldMode;
    private Path root;

    @Override protected void setUp() throws Exception {
        super.setUp();
        root = Path.of(getProject().getBasePath()); Files.createDirectories(root);
        var vf = WriteAction.compute(() -> LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root));
        PsiTestUtil.addContentRoot(getModule(), vf);
        var settings = com.intellij.mcpserver.settings.McpToolDisallowListSettings.getInstance();
        oldStates = settings.getToolStates(); settings.setToolStates(Map.of());
        var filter = com.intellij.mcpserver.settings.McpToolFilterSettings.getInstance();
        oldMode = filter.getInvocationMode(); filter.setInvocationMode(com.intellij.mcpserver.McpSessionInvocationMode.DIRECT);
        List<VcsDirectoryMapping> mappings = new ArrayList<>();
        for (String name : List.of("b", "a", "中文 空格", "d")) {
            Path repo = root.resolve(name); Files.createDirectories(repo);
            git(repo, "init", "-q");
            Files.writeString(repo.resolve("tracked.txt"), "before\n");
            Files.writeString(repo.resolve("rename.txt"), "rename\n");
            Files.writeString(repo.resolve(".gitignore"), "ignored/\n");
            git(repo, "add", ".");
            git(repo, "-c", "user.name=Sandbox", "-c", "user.email=sandbox@example.invalid", "commit", "-qm", "fixture");
            if (!name.equals("d")) {
                Files.writeString(repo.resolve("tracked.txt"), "staged\n"); git(repo, "add", "tracked.txt");
                Files.writeString(repo.resolve("tracked.txt"), "unstaged\n");
                git(repo, "mv", "rename.txt", "中文 renamed.txt");
                Files.writeString(repo.resolve("untracked.txt"), "new\n");
                Files.createDirectories(repo.resolve("ignored")); Files.writeString(repo.resolve("ignored/a.txt"), "ignored\n");
            }
            mappings.add(new VcsDirectoryMapping(repo.toString(), "Git"));
        }
        WriteAction.run(() -> vf.refresh(false, true));
        ProjectLevelVcsManager.getInstance(getProject()).setDirectoryMappings(mappings);
        background(() -> kotlinx.coroutines.BuildersKt.runBlocking(kotlinx.coroutines.Dispatchers.getDefault(),
                (scope, continuation) -> com.intellij.dvcs.repo.VcsRepositoryManager.getInstance(getProject()).ensureUpToDate(true, continuation)));
        assertEquals(4, GitRepositoryManager.getInstance(getProject()).getRepositories().size());
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (oldMode != null) com.intellij.mcpserver.settings.McpToolFilterSettings.getInstance().setInvocationMode(oldMode);
            if (oldStates != null) com.intellij.mcpserver.settings.McpToolDisallowListSettings.getInstance().setToolStates(oldStates);
            ProjectLevelVcsManager.getInstance(getProject()).setDirectoryMappings(List.of());
            if (root != null) try (var paths = Files.walk(root)) {
                for (Path path : paths.toList()) if (Files.isRegularFile(path)) path.toFile().setWritable(true);
            }
        } finally { super.tearDown(); }
    }

    public void testCompleteResultsFiltersLimitsAndFallbacks() throws Exception {
        background(() -> {
            for (boolean untracked : List.of(false, true)) for (boolean ignored : List.of(false, true)) for (int limit : List.of(1, 2, 10000)) {
                var args = args(untracked, ignored, limit);
                if (!ignored || untracked) assertNotNull(GitStatusBatch.tryCall(getProject(), args, deadline()));
                equal(args);
            }
            var selected = args(true, false, 10000); selected.addProperty("repositoryPathRelativeToProject", "a");
            assertNull(GitStatusBatch.tryCall(getProject(), selected, deadline())); equal(selected);
            assertNull(GitStatusBatch.tryCall(getProject(), object("projectPath", root.toString(), "repositoryPathRelativeToProject", "../outside"), deadline()));
            try { GitStatusBatch.tryCall(getProject(), args(true, false, 10000), System.nanoTime() - 1); fail("Expired query accepted"); }
            catch (CancellationException expected) { /* Shared deadline, no fallback with a fresh budget. */ }
            return null;
        });
        // A detached head remains null/omitted exactly as in the official response.
        git(root.resolve("d"), "checkout", "--detach", "-q");
        background(() -> { GitRepositoryManager.getInstance(getProject()).updateAllRepositories(); equal(args(true, true, 10000)); return null; });

        // Queued work shares the outer deadline and is cancelled before it starts.
        CountDownLatch entered = new CountDownLatch(4), release = new CountDownLatch(1);
        var workers = com.intellij.openapi.application.ApplicationManager.getApplication().getService(GitStatusBatch.Workers.class).executor;
        List<Future<?>> blockers = new ArrayList<>();
        for (int i = 0; i < 4; i++) blockers.add(workers.submit(() -> { entered.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }));
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        try {
            background(() -> {
                try { GitStatusBatch.tryCall(getProject(), args(true, false, 10000), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100)); fail("Queue ignored deadline"); }
                catch (TimeoutException | CancellationException expected) { }
                return null;
            });
        } finally { release.countDown(); for (Future<?> blocker : blockers) blocker.get(10, TimeUnit.SECONDS); }

        // Conservative fallbacks leave single, nested, and external roots to the SDK.
        map(List.of(root.resolve("a")));
        background(() -> { assertNull(GitStatusBatch.tryCall(getProject(), args(true, false, 10000), deadline())); equal(args(true, false, 10000)); return null; });
        Path nested = root.resolve("a/nested"); Files.createDirectories(nested); git(nested, "init", "-q");
        map(List.of(root.resolve("a"), nested));
        background(() -> { assertNull(GitStatusBatch.tryCall(getProject(), args(true, false, 10000), deadline())); equal(args(true, false, 10000)); return null; });
        VirtualFile external = myFixture.getTempDirFixture().findOrCreateDir("outside-repository");
        assertFalse(external.toNioPath().startsWith(root)); git(external.toNioPath(), "init", "-q");
        map(List.of(root.resolve("a"), external.toNioPath()));
        background(() -> { assertNull(GitStatusBatch.tryCall(getProject(), args(true, false, 10000), deadline())); equal(args(true, false, 10000)); return null; });

        String key = com.intellij.mcpserver.McpToolsProvider.Companion.getEP().getExtensionList().stream().flatMap(p -> p.getTools().stream()).filter(t -> t.getDescriptor().getName().equals("git_status")).findFirst().orElseThrow().getDescriptor().getFullyQualifiedName();
        assertTrue(key, key.contains("VcsToolset"));
        com.intellij.mcpserver.settings.McpToolDisallowListSettings.getInstance().setToolStates(Map.of(key, new com.intellij.mcpserver.settings.McpToolDisallowListSettings.ToolState(false, false)));
        background(() -> {
            try { new OfficialMcpAdapter().call("git_status", args(true, false, 10000)); fail("Disabled tool accepted"); }
            catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("PLATFORM_TOOL_UNAVAILABLE")); }
            return null;
        });
    }

    public void testFairFourRepositoryBenchmark() throws Exception {
        if (System.getProperty("idea.mcp.test.rg", "").isBlank()) return;
        JsonArray rows = new JsonArray(); JsonObject args = args(true, true, 10000);
        background(() -> {
            JsonObject expected = new OfficialMcpAdapter(false, false).call("git_status", args);
            assertEquals(4, expected.getAsJsonObject("structuredContent").getAsJsonArray("repositories").size());
            for (int pass = 0; pass < 2; pass++) for (int round = -3; round < 80; round++) {
                for (boolean optimized : round % 2 == 0 ? List.of(true, false) : List.of(false, true)) {
                    long start = System.nanoTime();
                    JsonObject result = new OfficialMcpAdapter(false, optimized).call("git_status", args);
                    double ms = (System.nanoTime() - start) / 1_000_000.0;
                    assertEquals(expected, result);
                    if (round >= 0) rows.add(object("task", "four_repositories", "pass", pass, "round", round, "provider", optimized ? "idea" : "official", "ms", ms));
                }
            }
            return null;
        });
        Path out = Path.of(System.getProperty("user.dir"), "build/benchmarks/064-query"); Files.createDirectories(out);
        Files.writeString(out.resolve("multi-root.json"), object("args", args, "samples", rows, "completeResultsEqual", true).toString());
    }

    public void testSameTransportFourRepositoryBenchmark() throws Exception {
        if (System.getProperty("idea.mcp.test.rg", "").isBlank()) return;
        JsonObject args = args(true, true, 10000); JsonArray rows = new JsonArray();
        String token = "fixture_git_batch_AAAAAAAAAAAAAAAAAAAAAAAA";
        try (var optimized = server(true, token); var original = server(false, token)) {
            optimized.start(); original.start();
            for (int roots : List.of(4, 1)) {
            if (roots == 1) map(List.of(root.resolve("a")));
            background(() -> {
                JsonObject expected = new OfficialMcpAdapter(false, false).call("git_status", args);
                try (var client = java.net.http.HttpClient.newHttpClient()) {
                    for (int round = -3; round < 80; round++) for (boolean fast : round % 2 == 0 ? List.of(true, false) : List.of(false, true)) {
                        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(fast ? optimized.url() : original.url()))
                                .timeout(java.time.Duration.ofSeconds(20)).header("Authorization", "Bearer " + token)
                                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(object("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", object("name", "git_status", "arguments", args)).toString())).build();
                        long start = System.nanoTime();
                        var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                        double ms = (System.nanoTime() - start) / 1_000_000.0;
                        assertEquals(200, response.statusCode()); var envelope = JsonParser.parseString(response.body()).getAsJsonObject();
                        assertFalse(envelope.toString(), envelope.has("error")); assertEquals(expected, envelope.getAsJsonObject("result"));
                        if (round >= 0) rows.add(object("task", roots == 4 ? "four_repositories_http" : "single_repository_http", "round", round, "provider", fast ? "idea" : "official", "ms", ms));
                    }
                }
                return null;
            });
            }
        }
        Path out = Path.of(System.getProperty("user.dir"), "build/benchmarks/064-query"); Files.createDirectories(out);
        Files.writeString(out.resolve("multi-root-http.json"), object("samples", rows, "completeResultsEqual", true).toString());
    }

    private dev.ideamcp.protocol.McpHttpServer server(boolean optimized, String token) throws Exception {
        var adapter = new OfficialMcpAdapter(false, optimized);
        var backend = new dev.ideamcp.protocol.RpcHandler.Backend() {
            @Override public JsonObject call(String name, JsonObject args) { throw new UnsupportedOperationException(); }
            @Override public JsonArray tools() { return adapter.tools(); }
            @Override public dev.ideamcp.protocol.RpcHandler.ToolCall prepare(String name) { return adapter.prepare(name); }
        };
        return new dev.ideamcp.protocol.McpHttpServer(0, token, new dev.ideamcp.protocol.RpcHandler(backend));
    }

    private JsonObject args(boolean untracked, boolean ignored, int limit) {
        return object("projectPath", root.toString(), "includeUntracked", untracked, "includeIgnored", ignored, "limit", limit);
    }
    private void map(List<Path> paths) throws Exception {
        WriteAction.run(() -> LocalFileSystem.getInstance().findFileByNioFile(root).refresh(false, true));
        ProjectLevelVcsManager.getInstance(getProject()).setDirectoryMappings(paths.stream().map(p -> new VcsDirectoryMapping(p.toString(), "Git")).toList());
        background(() -> kotlinx.coroutines.BuildersKt.runBlocking(kotlinx.coroutines.Dispatchers.getDefault(),
                (scope, continuation) -> com.intellij.dvcs.repo.VcsRepositoryManager.getInstance(getProject()).ensureUpToDate(true, continuation)));
    }
    private void equal(JsonObject args) throws Exception {
        assertEquals(new OfficialMcpAdapter(false, false).call("git_status", args), new OfficialMcpAdapter().call("git_status", args));
    }
    private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(20); }
    private void git(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString())); command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroy(); fail("Fixture Git timed out"); }
        assertEquals(output, 0, process.exitValue());
    }
    private <T> T background(Callable<T> action) throws Exception {
        return PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(action), 240_000);
    }
}
