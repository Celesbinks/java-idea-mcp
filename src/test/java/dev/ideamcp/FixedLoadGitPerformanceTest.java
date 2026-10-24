package dev.ideamcp;

import com.google.gson.*;
import com.intellij.openapi.application.*;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vfs.*;
import com.intellij.testFramework.*;
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase;
import com.intellij.util.concurrency.AppExecutorUtil;
import dev.ideamcp.protocol.*;
import git4idea.commands.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static dev.ideamcp.protocol.Json.object;

/** Real SDK, fixed predecessor before every sample; private access is test-only. */
public final class FixedLoadGitPerformanceTest extends JavaCodeInsightFixtureTestCase {
    private Path root;
    private GitCommandProbe gitProbe;
    private Map<String, com.intellij.mcpserver.settings.McpToolDisallowListSettings.ToolState> oldStates;
    private com.intellij.mcpserver.McpSessionInvocationMode oldMode;
    private static final String TOKEN = "fixture_fixed_load_AAAAAAAAAAAAAAAAAAAAAAAAA";

    @Override protected void tuneFixture(com.intellij.testFramework.builders.JavaModuleFixtureBuilder b) throws Exception {
        super.tuneFixture(b); b.addJdk(System.getProperty("java.home")); b.setLanguageLevel(com.intellij.pom.java.LanguageLevel.JDK_21);
    }
    @Override protected void setUp() throws Exception {
        super.setUp(); root = Path.of(getProject().getBasePath()); Files.createDirectories(root);
        var vf = WriteAction.compute(() -> LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root));
        PsiTestUtil.addContentRoot(getModule(), vf);
        Path repository = Path.of(System.getProperty("user.dir"));
        try (var files = Files.walk(repository.resolve("src/main/java"))) {
            for (Path source : files.filter(Files::isRegularFile).toList()) {
                Path target = root.resolve(repository.relativize(source)); Files.createDirectories(target.getParent()); Files.copy(source, target);
            }
        }
        WriteAction.run(() -> vf.refresh(false, true));
        PsiTestUtil.addSourceRoot(getModule(), LocalFileSystem.getInstance().findFileByNioFile(root.resolve("src/main/java")));
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        git("init", "-q"); git("config", "core.longpaths", "true");
        Files.writeString(root.resolve("tracked.txt"), "before\n"); Files.writeString(root.resolve("rename.txt"), "rename\n");
        Files.writeString(root.resolve(".gitignore"), "ignored/\n"); git("add", ".");
        git("-c", "user.name=Sandbox", "-c", "user.email=sandbox@example.invalid", "commit", "-qm", "fixture");
        Files.writeString(root.resolve("tracked.txt"), "staged\n"); git("add", "tracked.txt"); Files.writeString(root.resolve("tracked.txt"), "worktree\n");
        git("mv", "rename.txt", "中文 renamed.txt"); Files.writeString(root.resolve("untracked.txt"), "untracked\n");
        Files.createDirectories(root.resolve("ignored")); Files.writeString(root.resolve("ignored/a.txt"), "ignored\n");
        WriteAction.run(() -> vf.refresh(false, true));
        ProjectLevelVcsManager.getInstance(getProject()).setDirectoryMappings(List.of(new VcsDirectoryMapping(root.toString(), "Git")));
        background(() -> kotlinx.coroutines.BuildersKt.runBlocking(kotlinx.coroutines.Dispatchers.getDefault(),
                (scope, c) -> com.intellij.dvcs.repo.VcsRepositoryManager.getInstance(getProject()).ensureUpToDate(true, c)));
        assertEquals(1, git4idea.repo.GitRepositoryManager.getInstance(getProject()).getRepositories().size());
        var states = com.intellij.mcpserver.settings.McpToolDisallowListSettings.getInstance(); oldStates = states.getToolStates(); states.setToolStates(Map.of());
        var filter = com.intellij.mcpserver.settings.McpToolFilterSettings.getInstance(); oldMode = filter.getInvocationMode(); filter.setInvocationMode(com.intellij.mcpserver.McpSessionInvocationMode.DIRECT);
        installCommandProbe();
    }
    @Override protected void tearDown() throws Exception {
        try {
            if (oldStates != null) com.intellij.mcpserver.settings.McpToolDisallowListSettings.getInstance().setToolStates(oldStates);
            if (oldMode != null) com.intellij.mcpserver.settings.McpToolFilterSettings.getInstance().setInvocationMode(oldMode);
            ProjectLevelVcsManager.getInstance(getProject()).setDirectoryMappings(List.of());
            if (root != null) try (var paths = Files.walk(root)) { for (Path path : paths.toList()) if (Files.isRegularFile(path)) path.toFile().setWritable(true); }
        } finally { super.tearDown(); }
    }
    private void installCommandProbe() throws Exception { gitProbe = GitCommandProbe.install(getProject(), getTestRootDisposable()); }

    public void testCommandBoundaryCompleteness() throws Exception {
        background(() -> {
            try (var scope = QueryTiming.open(true)) {
                var context = QueryTiming.current();
                var result = new OfficialMcpAdapter(false, false).call("git_status", object("projectPath", root.toString(), "includeUntracked", true, "includeIgnored", false, "limit", 10000));
                assertFalse(result.toString(), result.get("isError").getAsBoolean());
                for (String phase : List.of("git_before_process", "git_process", "git_after_process", "git_command_total"))
                    assertTrue(phase + context.snapshot(), context.nanos(phase) >= 0);
                assertTrue(context.nanos("git_command_total") >= context.nanos("git_process"));
                var count = gitProbe.counts(context.id()); assertTrue(count.status() >= 1); assertEquals(0, count.failed());
            }
            assertNull(QueryTiming.current()); return null;
        });
    }

    public void testTimingCancellationAndTimeout() throws Exception {
        var descriptor = com.intellij.mcpserver.McpToolsProvider.Companion.getEP().getExtensionList().stream()
                .flatMap(p -> p.getTools().stream()).filter(t -> t.getDescriptor().getName().equals("git_status")).findFirst().orElseThrow().getDescriptor();
        CountDownLatch entered = new CountDownLatch(1), firstCompleted = new CountDownLatch(1), completed = new CountDownLatch(2);
        var fake = new com.intellij.mcpserver.McpTool() {
            @Override public com.intellij.mcpserver.McpToolDescriptor getDescriptor() { return descriptor; }
            @Override public Object call(kotlinx.serialization.json.JsonObject args, kotlin.coroutines.Continuation<? super com.intellij.mcpserver.McpToolCallResult> continuation) {
                assertNotNull(QueryTiming.current()); entered.countDown();
                continuation.getContext().get(kotlinx.coroutines.Job.Key).invokeOnCompletion(cause -> { completed.countDown(); firstCompleted.countDown(); return kotlin.Unit.INSTANCE; });
                return kotlinx.coroutines.DelayKt.delay(60_000, new kotlin.coroutines.Continuation<kotlin.Unit>() {
                    @Override public kotlin.coroutines.CoroutineContext getContext() { return continuation.getContext(); }
                    @Override public void resumeWith(Object result) {
                        Object completion;
                        try { kotlin.ResultKt.throwOnFailure(result); completion = kotlin.ResultKt.createFailure(new AssertionError("Delay completed normally")); }
                        catch (Throwable cancelled) { completion = result; }
                        continuation.resumeWith(completion);
                    }
                });
            }
        };
        ExtensionTestUtil.maskExtensions(com.intellij.mcpserver.McpToolsProvider.Companion.getEP(), List.of(() -> List.of(fake)), getTestRootDisposable());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        var dispatcher = kotlinx.coroutines.ExecutorsKt.from(worker);
        var args = object("projectPath", root.toString(), "includeUntracked", true, "includeIgnored", false, "limit", 10000);
        try {
            Future<?> parent = AppExecutorUtil.getAppExecutorService().submit(() -> {
                try (var scope = QueryTiming.open(true)) {
                    try { new OfficialMcpAdapter(false, false, dispatcher).call("git_status", args); throw new AssertionError("Cancelled call returned"); }
                    catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
                    catch (Exception failure) { throw new RuntimeException(failure); }
                }
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS)); parent.cancel(true);
            assertTrue(firstCompleted.await(10, TimeUnit.SECONDS));
            assertEquals(1, completed.getCount());
            assertNull(worker.submit(QueryTiming::current).get(10, TimeUnit.SECONDS));
            background(() -> {
                long start = System.nanoTime();
                try (var scope = QueryTiming.open(true)) {
                    try { new OfficialMcpAdapter(false, false, dispatcher).call("git_status", args); fail("Timeout ignored"); }
                    catch (kotlinx.coroutines.TimeoutCancellationException expected) { }
                    long elapsed = System.nanoTime() - start;
                    assertTrue("Deadline exceeded", elapsed < TimeUnit.SECONDS.toNanos(23));
                    assertTrue("Timeout returned early", elapsed >= TimeUnit.SECONDS.toNanos(19));
                    assertTrue(QueryTiming.current().snapshot().containsKey("adapter_provider_total"));
                }
                assertNull(QueryTiming.current()); return null;
            });
            assertTrue(completed.await(10, TimeUnit.SECONDS));
            assertNull(worker.submit(QueryTiming::current).get(10, TimeUnit.SECONDS));
        } finally { dispatcher.close(); worker.shutdownNow(); }
    }

    public void testFixedLoadSegments() throws Exception {
        if (System.getProperty("idea.mcp.test.rg", "").isBlank()) return;
        JsonObject gitArgs = object("projectPath", root.toString(), "includeUntracked", true, "includeIgnored", false, "limit", 10000);
        JsonObject treeArgs = object("projectPath", root.toString(), "symbolFqn", "dev.ideamcp.protocol.Arguments.required", "analysisKind", "INCOMING_CALLS", "depth", 1, "maxChildren", 200, "maxNodes", 10000, "childOffset", 0, "timeout", 10000);
        JsonArray rows = new JsonArray();
        try (var recording = new jdk.jfr.Recording(jdk.jfr.Configuration.create(Path.of("scripts/http-timing.jfc")));
             var original = server(new OfficialMcpAdapter(false, false), true);
             var current = server(new OfficialMcpAdapter(false, true), true);
             var candidate = thirdServer();
             var disabled = server(new OfficialMcpAdapter(false, true), false)) {
            recording.start(); original.start(); current.start(); candidate.start(); disabled.start();
            String third = System.getProperty("idea.mcp.test.baselineJar", "").isBlank() ? "io" : "legacy";
            background(() -> {
                try (var client = HttpClient.newHttpClient()) {
                    JsonObject expected = send(client, original.url(), "git_status", gitArgs).result;
                    assertFalse(expected.toString(), expected.get("isError").getAsBoolean());
                    var repository = expected.getAsJsonObject("structuredContent").getAsJsonArray("repositories").get(0).getAsJsonObject();
                    assertFalse(repository.get("hasMoreEntries").getAsBoolean()); assertEquals(repository.get("totalEntries").getAsInt(), repository.getAsJsonArray("entries").size());
                    JsonObject expectedTree = send(client, original.url(), "analyze_calls", treeArgs).result;
                    assertFalse(expectedTree.toString(), expectedTree.get("isError").getAsBoolean());
                    assertEquals(14, expectedTree.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString().lines().count());
                    Map<String, String> urls = Map.of("official", original.url(), "current", current.url(), third, candidate.url());
                    // 84 is a multiple of the six permutations: every position and every
                    // pairwise precedence is exactly balanced, including the third control.
                    for (int pass = 0; pass < 2; pass++) for (boolean predecessor : List.of(false, true)) for (int round = -3; round < 84; round++) {
                        List<String> order = new ArrayList<>(List.of("official", "current", third));
                        Collections.rotate(order, Math.floorMod(round, 3)); if ((round & 1) != 0) Collections.reverse(order);
                        for (String provider : order) {
                            double preMs = 0;
                            if (predecessor) { Reply pre = send(client, original.url(), "analyze_calls", treeArgs); assertEquals(expectedTree, pre.result); preMs = pre.ms; }
                            long[] gcBefore = gc(); Reply reply = send(client, urls.get(provider), "git_status", gitArgs); long[] gcAfter = gc();
                            assertEquals(expected, reply.result);
                            for (String stage : List.of("git_process", "git_before_process", "git_after_process"))
                                assertTrue(reply.phases.toString(), reply.phases.containsKey(stage));
                            var counts = gitProbe.counts(reply.timingId); assertTrue(counts.status() >= 1); assertEquals(0, counts.failed());
                            assertTrue(reply.phases.toString(), reply.phases.containsKey("adapter_dispatch"));
                            if (round >= 0) rows.add(object("pass", pass, "predecessor", predecessor, "round", round, "provider", provider, "position", order.indexOf(provider), "preMs", preMs, "ms", reply.ms,
                                    "timingId", reply.timingId, "statusCommands", counts.status(), "otherCommands", counts.other(), "failedCommands", counts.failed(), "phases", reply.phases, "gcCountDelta", gcAfter[0] - gcBefore[0], "gcMsDelta", gcAfter[1] - gcBefore[1]));
                            if (round >= 0 && round % 14 == 13 && order.indexOf(provider) == 2) {
                                Path progress = Path.of(System.getProperty("user.dir"), "build/benchmarks/066-command-http/sandbox-progress.json");
                                Files.createDirectories(progress.getParent());
                                Files.writeString(progress, object("pass", pass, "predecessor", predecessor, "round", round, "samples", rows.size()).toString());
                            }
                        }
                    }
                    for (int round = -3; round < 80; round++) for (boolean enabled : round % 2 == 0 ? List.of(false, true) : List.of(true, false)) {
                        Reply reply = send(client, enabled ? current.url() : disabled.url(), "git_status", gitArgs); assertEquals(expected, reply.result);
                        assertEquals(enabled, !reply.phases.isEmpty());
                        if (round >= 0) rows.add(object("task", "timing_overhead", "round", round, "provider", enabled ? "enabled" : "disabled", "ms", reply.ms, "timingId", enabled ? reply.timingId : null));
                    }
                    Path out = Path.of(System.getProperty("user.dir"), "build/benchmarks/066-command-http"); Files.createDirectories(out);
                    Files.writeString(out.resolve("sandbox.json"), object("thirdControl", third, "samples", rows, "gitArgs", gitArgs, "treeArgs", treeArgs, "expectedGit", expected, "expectedTree", expectedTree).toString());
                }
                return null;
            });
            Thread.sleep(100); // Only after all timed requests, to finish the final event publication.
            recording.stop();
            Path out = Path.of(System.getProperty("user.dir"), "build/benchmarks/066-command-http");
            recording.dump(out.resolve("sandbox-http.jfr"));
            JsonArray completions = new JsonArray(); Map<Long, jdk.jfr.consumer.RecordedEvent> byId = new HashMap<>();
            for (var event : jdk.jfr.consumer.RecordingFile.readAllEvents(out.resolve("sandbox-http.jfr"))) {
                assertEquals(HttpTimingEvent.NAME, event.getEventType().getName());
                assertNull(byId.put(event.getLong("requestId"), event));
                JsonObject json = object("timingId", event.getLong("requestId"), "responseCompleted", event.getBoolean("responseCompleted"));
                for (String field : List.of("executorWait", "taskToHandler", "admission", "read", "parse", "rpc", "provider", "serialize", "headersPrepare", "headersSend", "bodyWrite", "exchangeClose", "handlerThroughClose", "taskThroughClose"))
                    json.addProperty(field, event.getLong(field) < 0 ? null : event.getLong(field) / 1_000_000.0);
                completions.add(json);
            }
            for (var value : rows) {
                var row = value.getAsJsonObject();
                if (!row.has("task") && !row.get("provider").getAsString().equals("legacy")) {
                    var event = byId.get(row.get("timingId").getAsLong()); assertNotNull(event);
                    assertTrue(event.getBoolean("responseCompleted")); assertTrue(event.getLong("provider") >= 0);
                }
            }
            Files.writeString(out.resolve("sandbox-http.json"), object("events", completions).toString());
        }
    }
    /** Diagnostic-only run; park/monitor stacks are never mixed into fair benchmark samples. */
    public void testProcessWaitDiagnostics() throws Exception {
        if (System.getProperty("idea.mcp.test.rg", "").isBlank()) return;
        Path out = Path.of(System.getProperty("user.dir"), "build/benchmarks/066-command-http"); Files.createDirectories(out);
        JsonArray rows = new JsonArray();
        JsonObject gitArgs = object("projectPath", root.toString(), "includeUntracked", true, "includeIgnored", false, "limit", 10000);
        JsonObject treeArgs = object("projectPath", root.toString(), "symbolFqn", "dev.ideamcp.protocol.Arguments.required", "analysisKind", "INCOMING_CALLS", "depth", 1, "maxChildren", 200, "maxNodes", 10000, "childOffset", 0, "timeout", 10000);
        try (var recording = new jdk.jfr.Recording(jdk.jfr.Configuration.create(Path.of("scripts/http-timing.jfc")));
             var server = server(new OfficialMcpAdapter(false, false), true)) {
            recording.enable(GitCommandProbe.CommandEvent.class).withoutStackTrace();
            for (String name : List.of("jdk.ThreadPark", "jdk.JavaMonitorWait", "jdk.JavaMonitorEnter"))
                recording.enable(name).withThreshold(java.time.Duration.ZERO).withStackTrace();
            recording.enable("jdk.GarbageCollection").withoutStackTrace(); server.start();
            background(() -> {
                try (var client = HttpClient.newHttpClient()) {
                    var expected = send(client, server.url(), "git_status", gitArgs).result;
                    var tree = send(client, server.url(), "analyze_calls", treeArgs).result;
                    for (int warm = 0; warm < 3; warm++) assertEquals(expected, send(client, server.url(), "git_status", gitArgs).result);
                    recording.start();
                    for (boolean predecessor : List.of(false, true)) for (int round = 0; round < 20; round++) {
                        if (predecessor) assertEquals(tree, send(client, server.url(), "analyze_calls", treeArgs).result);
                        var reply = send(client, server.url(), "git_status", gitArgs); assertEquals(expected, reply.result);
                        rows.add(object("predecessor", predecessor, "round", round, "timingId", reply.timingId, "ms", reply.ms, "phases", reply.phases));
                    }
                }
                return null;
            });
            Thread.sleep(100); recording.stop(); recording.dump(out.resolve("sandbox-wait.jfr"));
        }
        var events = jdk.jfr.consumer.RecordingFile.readAllEvents(out.resolve("sandbox-wait.jfr"));
        Map<Long, jdk.jfr.consumer.RecordedEvent> commands = new HashMap<>();
        for (var event : events) if (event.getEventType().getName().equals("dev.ideamcp.test.GitCommand") && event.getBoolean("statusCommand"))
            assertNull("More than one status command in one query", commands.put(event.getLong("requestId"), event));
        long observedWaits = 0;
        for (var value : rows) {
            var row = value.getAsJsonObject(); var command = commands.get(row.get("timingId").getAsLong()); assertNotNull(command);
            long thread = command.getThread().getJavaThreadId(), wait = 0, otherPark = 0, lock = 0, gc = 0; int waitEvents = 0;
            for (var event : events) {
                String type = event.getEventType().getName();
                if (type.equals("jdk.GarbageCollection")) {
                    var start = event.getStartTime().isAfter(command.getStartTime()) ? event.getStartTime() : command.getStartTime();
                    var end = event.getEndTime().isBefore(command.getEndTime()) ? event.getEndTime() : command.getEndTime();
                    if (end.isAfter(start)) gc += java.time.Duration.between(start, end).toNanos();
                    continue;
                }
                if (!List.of("jdk.ThreadPark", "jdk.JavaMonitorWait", "jdk.JavaMonitorEnter").contains(type) || event.getThread() == null
                        || event.getThread().getJavaThreadId() != thread || event.getStartTime().isBefore(command.getStartTime()) || event.getEndTime().isAfter(command.getEndTime())) continue;
                boolean processWait = event.getStackTrace() != null && event.getStackTrace().getFrames().stream().anyMatch(frame ->
                        frame.getMethod().getType().getName().equals("git4idea.commands.GitTextHandler") && frame.getMethod().getName().equals("waitForProcess"));
                long duration = event.getDuration().toNanos();
                if (type.equals("jdk.JavaMonitorEnter")) lock += duration;
                else if (processWait) { wait += duration; waitEvents++; } else otherPark += duration;
            }
            observedWaits += waitEvents;
            row.addProperty("processWaitMs", wait / 1_000_000.0); row.addProperty("processWaitEvents", waitEvents);
            row.addProperty("otherParkMs", otherPark / 1_000_000.0); row.addProperty("monitorEnterMs", lock / 1_000_000.0);
            row.addProperty("gcOverlapMs", gc / 1_000_000.0);
        }
        assertTrue("No process wait stacks observed", observedWaits > 0);
        Files.writeString(out.resolve("sandbox-wait.json"), object("diagnosticOnly", true, "samples", rows).toString());
    }
    private static long[] gc() {
        long count = 0, ms = 0; for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) { count += Math.max(0, bean.getCollectionCount()); ms += Math.max(0, bean.getCollectionTime()); } return new long[]{count, ms};
    }
    private record Reply(JsonObject result, double ms, Map<String, Double> phases, long timingId) {}
    private Reply send(HttpClient client, String url, String name, JsonObject args) throws Exception {
        var request = HttpRequest.newBuilder(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + TOKEN).header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(object("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", object("name", name, "arguments", args)).toString())).build();
        long start = System.nanoTime(); var response = client.send(request, HttpResponse.BodyHandlers.ofString()); double ms = (System.nanoTime() - start) / 1_000_000.0;
        assertEquals(200, response.statusCode()); var envelope = JsonParser.parseString(response.body()).getAsJsonObject(); assertFalse(envelope.toString(), envelope.has("error"));
        Map<String, Double> phases = new TreeMap<>(); response.headers().firstValue("Server-Timing").ifPresent(header -> {
            for (String item : header.split(",")) { String[] pair = item.split(";dur="); phases.put(pair[0], Double.parseDouble(pair[1])); }
        });
        return new Reply(envelope.getAsJsonObject("result"), ms, phases, response.headers().firstValue("X-Java-Mcp-Timing-Id").map(Long::parseLong).orElseGet(gitProbe::lastRequestId));
    }
    private McpHttpServer server(OfficialMcpAdapter adapter, boolean timing) throws Exception {
        var backend = new RpcHandler.Backend() {
            @Override public JsonObject call(String name, JsonObject args) { throw new UnsupportedOperationException(); }
            @Override public JsonArray tools() { return adapter.tools(); }
            @Override public RpcHandler.ToolCall prepare(String name) { return adapter.prepare(name); }
        };
        return new McpHttpServer(0, TOKEN, new RpcHandler(backend), timing);
    }
    private interface ThirdServer extends AutoCloseable { void start() throws Exception; String url() throws Exception; }
    private ThirdServer thirdServer() throws Exception {
        String jar = System.getProperty("idea.mcp.test.baselineJar", "");
        if (jar.isBlank()) {
            var value = server(new OfficialMcpAdapter(false, true, kotlinx.coroutines.Dispatchers.getIO()), true);
            return new ThirdServer() {
                public void start() { value.start(); }
                public String url() { return value.url(); }
                public void close() { value.close(); }
            };
        }
        var loader = new java.net.URLClassLoader(new java.net.URL[]{Path.of(jar).toUri().toURL()}, McpHttpServer.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (!name.equals(McpHttpServer.class.getName())) return super.loadClass(name, resolve);
                    Class<?> type = findLoadedClass(name); if (type == null) type = findClass(name);
                    if (resolve) resolveClass(type); return type;
                }
            }
        };
        try {
            Class<?> type = loader.loadClass(McpHttpServer.class.getName());
            var adapter = new OfficialMcpAdapter(false, true);
            var backend = new RpcHandler.Backend() {
                @Override public JsonObject call(String name, JsonObject args) { throw new UnsupportedOperationException(); }
                @Override public JsonArray tools() { return adapter.tools(); }
                @Override public RpcHandler.ToolCall prepare(String name) { return adapter.prepare(name); }
            };
            Object value = type.getConstructor(int.class, String.class, RpcHandler.class, boolean.class).newInstance(0, TOKEN, new RpcHandler(backend), true);
            return new ThirdServer() {
                public void start() throws Exception { type.getMethod("start").invoke(value); }
                public String url() throws Exception { return (String) type.getMethod("url").invoke(value); }
                public void close() throws Exception { try { type.getMethod("close").invoke(value); } finally { loader.close(); } }
            };
        } catch (Exception failure) { loader.close(); throw failure; }
    }
    private void git(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.longpaths=true", "-C", root.toString())); command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start(); String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroy(); fail("Fixture Git timed out"); } assertEquals(output, 0, process.exitValue());
    }
    private <T> T background(Callable<T> action) throws Exception { return PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(action), 600_000); }
}
