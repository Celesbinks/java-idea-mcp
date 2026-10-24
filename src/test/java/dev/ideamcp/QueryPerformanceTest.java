package dev.ideamcp;

import com.google.gson.*;
import com.intellij.mcpserver.toolsets.general.AnalysisToolset;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.vfs.*;
import com.intellij.testFramework.*;
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase;
import com.intellij.util.concurrency.AppExecutorUtil;
import git4idea.commands.*;
import git4idea.index.GitIndexStatusUtilKt;
import kotlinx.coroutines.*;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import static dev.ideamcp.protocol.Json.object;

/** Opt-in, real SDK profiling. Reflection is confined to this diagnostic test. */
public final class QueryPerformanceTest extends JavaCodeInsightFixtureTestCase {
    private final JsonArray rows = new JsonArray();

    @Override protected void tuneFixture(com.intellij.testFramework.builders.JavaModuleFixtureBuilder b) throws Exception {
        super.tuneFixture(b); b.addJdk(System.getProperty("java.home"));
        b.setLanguageLevel(com.intellij.pom.java.LanguageLevel.JDK_21);
    }

    @Override protected void tearDown() throws Exception {
        try {
            Path root = Path.of(getProject().getBasePath());
            if (Files.isDirectory(root)) try (var files = Files.walk(root)) {
                for (Path path : files.toList()) if (Files.isRegularFile(path)) path.toFile().setWritable(true);
            }
        } finally { super.tearDown(); }
    }

    public void testGitProcessAndCallResolutionProfile() throws Exception {
        if (System.getProperty("idea.mcp.test.rg", "").isBlank()) return;
        Path output = Path.of(System.getProperty("user.dir"), "build/benchmarks/064-query");
        Files.createDirectories(output);
        Path root = Path.of(getProject().getBasePath());
        Files.createDirectories(root);
        git(root, "init", "-q");
        Files.writeString(root.resolve("tracked.txt"), "before\n");
        Files.writeString(root.resolve("renamed.txt"), "rename\n");
        Files.writeString(root.resolve(".gitignore"), "ignored/\n");
        git(root, "add", ".");
        git(root, "-c", "user.name=Sandbox", "-c", "user.email=sandbox@example.invalid", "commit", "-qm", "fixture");
        git(root, "mv", "renamed.txt", "中文 name.txt");
        Files.writeString(root.resolve("tracked.txt"), "after\n");
        Files.writeString(root.resolve("untracked.txt"), "untracked\n");
        Files.createDirectories(root.resolve("ignored"));
        Files.writeString(root.resolve("ignored/a.txt"), "ignored\n");
        VirtualFile vf = WriteAction.compute(() -> LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root));
        background(() -> {
            Method parse = GitIndexStatusUtilKt.class.getDeclaredMethod("parseGitStatusOutput", String.class);
            parse.setAccessible(true);
            for (int round = -3; round < 80; round++) {
                Object expected = null;
                for (String provider : round % 2 == 0 ? List.of("line", "binary") : List.of("binary", "line")) {
                    long start = System.nanoTime(); String raw;
                    if (provider.equals("line")) {
                        var h = new GitLineHandler(getProject(), vf, GitCommand.STATUS);
                        h.setSilent(true); h.addParameters("--porcelain", "-z", "--untracked-files=all", "--ignored=matching");
                        raw = Git.getInstance().runCommand(h).getOutputOrThrow();
                    } else {
                        var h = new GitBinaryHandler(getProject(), vf, GitCommand.STATUS);
                        h.setSilent(true); h.setStdoutSuppressed(true); h.setStderrSuppressed(true);
                        h.addParameters("--porcelain", "-z", "--untracked-files=all", "--ignored=matching");
                        raw = new String(h.run(), h.getCharset());
                    }
                    Object parsed = parse.invoke(null, raw);
                    double ms = (System.nanoTime() - start) / 1_000_000.0;
                    if (expected == null) expected = parsed; else assertEquals(expected, parsed);
                    if (round >= 0) rows.add(object("task", "git_process", "provider", provider, "round", round, "ms", ms));
                }
            }
            return null;
        });
        Files.writeString(output.resolve("profile.json"), rows.toString());

        var src = WriteAction.compute(() -> VfsUtil.createDirectories(root + "/src"));
        PsiTestUtil.addContentRoot(getModule(), vf); PsiTestUtil.addSourceRoot(getModule(), src);
        myFixture.addFileToProject("src/probe/Target.java", "package probe; public class Target { public static int target(int a) { return a; } public int caller(){return target(1);} }");
        Path repository = Path.of(System.getProperty("user.dir"));
        try (var files = Files.walk(repository.resolve("src/main/java"))) {
            for (Path source : files.filter(Files::isRegularFile).toList()) {
                Path target = root.resolve("src").resolve(repository.resolve("src/main/java").relativize(source));
                Files.createDirectories(target.getParent()); Files.copy(source, target);
            }
        }
        WriteAction.run(() -> vf.refresh(false, true));
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        background(() -> {
            Class<?> requestType = Class.forName("com.intellij.mcpserver.toolsets.general.CallHierarchyRequest");
            var constructor = requestType.getDeclaredConstructors()[0]; constructor.setAccessible(true);
            Class<?> analyzerType = Class.forName("com.intellij.mcpserver.toolsets.general.CallHierarchyAnalyzer");
            var analyzerConstructor = analyzerType.getDeclaredConstructor(com.intellij.openapi.project.Project.class, requestType); analyzerConstructor.setAccessible(true);
            Method resolve = analyzerType.getDeclaredMethod("resolveTargetSymbol", String.class, kotlin.coroutines.Continuation.class); resolve.setAccessible(true);
            Method analyze = analyzerType.getDeclaredMethod("analyze", kotlin.coroutines.Continuation.class); analyze.setAccessible(true);
            for (int round = -3; round < 80; round++) {
                for (String stage : round % 2 == 0 ? List.of("resolve", "full") : List.of("full", "resolve")) {
                    String symbol = "dev.ideamcp.protocol.Arguments.required";
                    Object request = constructor.newInstance(symbol, AnalysisToolset.AnalysisKind.INCOMING_CALLS, 1, 200, 10000, List.of(), 0, 10000);
                    Object analyzer = analyzerConstructor.newInstance(getProject(), request);
                    long start = System.nanoTime();
                    Object result = BuildersKt.runBlocking(Dispatchers.getDefault(), (scope, continuation) -> {
                        try { return stage.equals("resolve") ? resolve.invoke(analyzer, symbol, continuation) : analyze.invoke(analyzer, continuation); }
                        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
                    });
                    assertNotNull(result);
                    if (stage.equals("full")) {
                        assertTrue(result.toString(), result.toString().contains("required("));
                        assertEquals(14, result.toString().lines().count());
                        assertFalse(result.toString(), result.toString().contains("… and"));
                    }
                    if (round >= 0) rows.add(object("task", "calls_stage", "provider", stage, "round", round, "ms", (System.nanoTime() - start) / 1_000_000.0));
                }
            }
            return null;
        });
        Files.writeString(output.resolve("profile.json"), rows.toString());
    }

    private void git(Path root, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString())); command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String response = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(response, 0, p.exitValue());
    }

    private <T> T background(Callable<T> action) throws Exception {
        return PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(action), 240_000);
    }
}
