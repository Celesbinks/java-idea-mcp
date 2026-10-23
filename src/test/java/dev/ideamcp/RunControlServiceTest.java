package dev.ideamcp;

import com.google.gson.*;
import com.intellij.execution.*;
import com.intellij.execution.application.*;
import com.intellij.execution.impl.ExecutionManagerImpl;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.BaseProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.openapi.util.Key;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.testFramework.*;
import com.intellij.testFramework.builders.JavaModuleFixtureBuilder;
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArraySet;
import com.intellij.util.concurrency.AppExecutorUtil;
import dev.ideamcp.protocol.RpcHandler;
import java.io.OutputStream;
import javax.swing.JPanel;
import static dev.ideamcp.protocol.Json.object;

/** Integration coverage launches only a disposable test JVM, never an existing user service. */
public final class RunControlServiceTest extends JavaCodeInsightFixtureTestCase {
    private RunControlService service;
    private final StringBuffer probeOutput = new StringBuffer();
    private final List<ControlledHandler> controlledHandlers = new ArrayList<>();
    private final Set<ProcessHandler> observedHandlers = new CopyOnWriteArraySet<>();
    @Override protected void tuneFixture(JavaModuleFixtureBuilder moduleBuilder) throws Exception {
        super.tuneFixture(moduleBuilder);
        moduleBuilder.addJdk(System.getProperty("idea.mcp.test.jdk", System.getProperty("java.home")));
        moduleBuilder.setLanguageLevel(LanguageLevel.JDK_21);
    }
    @Override protected void setUp() throws Exception {
        super.setUp();
        // Heavy Java fixtures label their SDK as Java 5 even when addJdk points
        // at a real JDK. Execution needs accurate metadata as well as real roots.
        WriteAction.run(() -> {
            var sdk = ModuleRootManager.getInstance(getModule()).getSdk();
            assertNotNull(sdk);
            var modifier = sdk.getSdkModificator();
            modifier.setVersionString("java version \"21\"");
            modifier.commitChanges();
        });
        service = getProject().getService(RunControlService.class);
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionListener() {
            @Override public void processStarting(String executorId, ExecutionEnvironment env, ProcessHandler handler) {
                observedHandlers.add(handler);
                handler.addProcessListener(new ProcessAdapter() {
                    @Override public void onTextAvailable(ProcessEvent event, Key outputType) { probeOutput.append(event.getText()); }
                }, getTestRootDisposable());
            }
            @Override public void processStarted(String executorId, ExecutionEnvironment env, ProcessHandler handler) { observedHandlers.add(handler); }
        });
    }
    @Override protected void tearDown() throws Exception {
        try {
            controlledHandlers.forEach(ControlledHandler::complete);
            Set<ProcessHandler> processes = new HashSet<>(observedHandlers);
            processes.addAll(Arrays.asList(ExecutionManager.getInstance(getProject()).getRunningProcesses()));
            for (RunContentDescriptor descriptor : ExecutionManagerImpl.getAllDescriptors(getProject())) {
                ProcessHandler handler = descriptor.getProcessHandler();
                if (handler != null) processes.add(handler);
            }
            for (ProcessHandler handler : processes) if (!handler.isProcessTerminating() && !handler.isProcessTerminated()) ExecutionManagerImpl.stopProcess(handler);
            PlatformTestUtil.waitWithEventsDispatching("Test JVMs did not stop", () -> processes.stream().allMatch(handler -> handler.isProcessTerminated()
                    && (!(handler instanceof BaseProcessHandler<?> nativeHandler) || !nativeHandler.getProcess().isAlive())), 15000);
        } finally { super.tearDown(); }
    }
    public void testListsExactIdsAndRejectsUnknownConfiguration() {
        RunnerAndConfigurationSettings settings = application("MCP test list", false);
        JsonObject result = service.call("list_run_configurations", object("name", "TEST LIST"));
        JsonArray configurations = result.getAsJsonArray("configurations");
        assertEquals(1, configurations.size());
        JsonObject listed = configurations.get(0).getAsJsonObject();
        assertEquals(settings.getUniqueID(), listed.get("id").getAsString());
        assertTrue(listed.get("canRun").getAsBoolean());
        assertTrue(listed.get("canDebug").getAsBoolean());
        assertEquals(0, listed.getAsJsonArray("sessions").size());
        assertThrows(IllegalArgumentException.class, () -> service.call("restart_run_configuration", object("configurationId", "missing")));
        assertThrows(IllegalArgumentException.class, () -> service.call("get_run_operation", object("operationId", "missing")));
        assertEquals(0, ExecutionManager.getInstance(getProject()).getRunningProcesses().length);
    }
    public void testRunDebugAndPreserveRestartWithRealJvm() throws Exception {
        RunnerAndConfigurationSettings settings = application("MCP disposable service", true);
        JsonObject run = restart(settings, "run");
        JsonObject duplicate = restart(settings, "run");
        assertEquals(run.get("operationId"), duplicate.get("operationId"));
        run = await(run);
        assertEquals("run", run.get("mode").getAsString());
        assertEquals(run + "\n" + probeOutput, "started", run.get("phase").getAsString());
        assertFalse(run.get("debuggerAttached").getAsBoolean());
        long initialPid = run.getAsJsonObject("process").get("pid").getAsLong();

        JsonObject debug = await(restart(settings, "debug"));
        assertEquals(debug + "\n" + probeOutput, "started", debug.get("phase").getAsString());
        assertEquals("debug", debug.get("mode").getAsString());
        assertEquals(1, debug.get("stoppedProcessCount").getAsInt());
        assertTrue(debug.toString(), debug.get("debuggerAttached").getAsBoolean());
        assertFalse(ProcessHandle.of(initialPid).map(ProcessHandle::isAlive).orElse(false));
        long debugPid = debug.getAsJsonObject("process").get("pid").getAsLong();

        JsonObject preserved = await(restart(settings, "preserve"));
        assertEquals("debug", preserved.get("mode").getAsString());
        assertTrue(preserved.get("debuggerAttached").getAsBoolean());
        assertEquals(1, preserved.get("stoppedProcessCount").getAsInt());
        assertFalse(ProcessHandle.of(debugPid).map(ProcessHandle::isAlive).orElse(false));
        JsonObject listed = service.call("list_run_configurations", object("name", "disposable"));
        JsonObject session = listed.getAsJsonArray("configurations").get(0).getAsJsonObject().getAsJsonArray("sessions").get(0).getAsJsonObject();
        assertTrue(session.getAsJsonArray("modes").toString().contains("debug"));
    }
    public void testValidationFailsBeforeAnyProcessIsStopped() throws Exception {
        RunnerAndConfigurationSettings settings = application("MCP invalid settings", false);
        JsonObject failed = await(restart(settings, "run"));
        assertEquals("failed", failed.get("phase").getAsString());
        assertEquals(0, failed.get("stoppedProcessCount").getAsInt());
        assertFalse(failed.get("inProgress").getAsBoolean());
        settings.setEditBeforeRun(true);
        failed = await(restart(settings, "debug"));
        assertEquals("failed", failed.get("phase").getAsString());
        assertTrue(failed.get("error").getAsString().contains("EDIT_BEFORE_RUN"));
    }
    public void testModeSelectionRejectsAmbiguity() {
        assertEquals("run", RunControlService.chooseMode("preserve", Set.of(), false));
        assertEquals("debug", RunControlService.chooseMode("preserve", Set.of("debug"), true));
        assertEquals("run", RunControlService.chooseMode("run", Set.of("run", "debug"), true));
        assertThrows(IllegalArgumentException.class, () -> RunControlService.chooseMode("preserve", Set.of("run", "debug"), true));
        assertThrows(IllegalArgumentException.class, () -> RunControlService.chooseMode("preserve", Set.of(), true));
    }
    public void testBackendDispatchesFromWorkerToIdeaUiThread() throws Exception {
        RunnerAndConfigurationSettings settings = application("MCP backend dispatch", false);
        var response = PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(() ->
                new RpcHandler(new IdeaToolBackend()).handle(object("jsonrpc", "2.0", "id", 10, "method", "tools/call", "params",
                        object("name", "list_run_configurations", "arguments", object("project", getProject().getBasePath(), "name", "backend dispatch"))))), 10000);
        JsonObject result = response.getAsJsonObject("result");
        assertFalse(result.toString(), result.get("isError").getAsBoolean());
        assertEquals(settings.getUniqueID(), result.getAsJsonObject("structuredContent").getAsJsonArray("configurations").get(0).getAsJsonObject().get("id").getAsString());
    }
    public void testStopTimeoutNeverLaunchesReplacementEvenAfterLateExit() throws Exception {
        RunnerAndConfigurationSettings settings = application("MCP controlled stop", true);
        ControlledRunner runner = new ControlledRunner(false);
        ExtensionTestUtil.maskExtensions(ProgramRunner.PROGRAM_RUNNER_EP, List.of(runner), getTestRootDisposable());
        assertEquals("started", await(restart(settings, "run")).get("phase").getAsString());
        assertEquals(1, runner.executions);
        JsonObject operation = service.call("restart_run_configuration", object("configurationId", settings.getUniqueID(), "mode", "run", "stopTimeoutSeconds", 1));
        JsonObject failed = await(operation);
        assertEquals("failed", failed.get("phase").getAsString());
        assertTrue(failed.toString(), failed.get("error").getAsString().contains("STOP_TIMEOUT"));
        assertEquals(0, failed.get("stoppedProcessCount").getAsInt());
        assertEquals(1, runner.executions);
        assertTrue(controlledHandlers.getFirst().destroyRequests > 0);
        controlledHandlers.getFirst().complete();
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
        assertEquals(1, runner.executions);
    }
    public void testStartTimeoutRetainsOperationAndIgnoresOtherExecutionEvents() throws Exception {
        RunnerAndConfigurationSettings settings = application("MCP controlled startup", true);
        ControlledRunner runner = new ControlledRunner(true);
        ExtensionTestUtil.maskExtensions(ProgramRunner.PROGRAM_RUNNER_EP, List.of(runner), getTestRootDisposable());
        JsonObject submitted = service.call("restart_run_configuration", object("configurationId", settings.getUniqueID(), "mode", "run", "startTimeoutSeconds", 1));
        String id = submitted.get("operationId").getAsString();
        PlatformTestUtil.waitWithEventsDispatching("No start timeout", () -> service.call("get_run_operation", object("operationId", id)).get("phase").getAsString().equals("start_timeout"), 10000);
        JsonObject pending = service.call("get_run_operation", object("operationId", id));
        assertTrue(pending.get("inProgress").getAsBoolean());
        assertEquals(submitted.get("operationId"), restart(settings, "run").get("operationId"));
        assertThrows(IllegalArgumentException.class, () -> restart(settings, "debug"));
        var unrelated = new com.intellij.execution.runners.ExecutionEnvironmentBuilder(runner.lastEnvironment).build();
        getProject().getMessageBus().syncPublisher(ExecutionManager.EXECUTION_TOPIC).processNotStarted("Run", unrelated);
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
        assertTrue(service.call("get_run_operation", object("operationId", id)).get("inProgress").getAsBoolean());
        getProject().getMessageBus().syncPublisher(ExecutionManager.EXECUTION_TOPIC).processNotStarted("Run", runner.lastEnvironment);
        JsonObject failed = await(submitted);
        assertEquals("failed", failed.get("phase").getAsString());
        assertEquals(1, runner.executions);
    }
    private final class ControlledRunner implements ProgramRunner<RunnerSettings> {
        final boolean stallStart;
        int executions;
        ExecutionEnvironment lastEnvironment;
        ControlledRunner(boolean stallStart) { this.stallStart = stallStart; }
        @Override public String getRunnerId() { return "JavaMcpControlledTestRunner"; }
        @Override public boolean canRun(String executorId, RunProfile profile) { return profile.getName().startsWith("MCP controlled"); }
        @Override public void execute(ExecutionEnvironment environment) {
            executions++;
            lastEnvironment = environment;
            if (stallStart) return;
            ControlledHandler handler = new ControlledHandler();
            controlledHandlers.add(handler);
            ExecutionManager.getInstance(getProject()).startRunProfile(new RunProfileStarter() {
                @Override public org.jetbrains.concurrency.Promise<RunContentDescriptor> executeAsync(ExecutionEnvironment env) {
                    var promise = new org.jetbrains.concurrency.AsyncPromise<RunContentDescriptor>();
                    promise.setResult(new RunContentDescriptor(null, handler, new JPanel(), environment.getRunProfile().getName()));
                    return promise;
                }
            }, environment);
        }
    }
    private static final class ControlledHandler extends ProcessHandler {
        int destroyRequests;
        @Override protected void destroyProcessImpl() { destroyRequests++; }
        @Override protected void detachProcessImpl() { complete(); }
        @Override public boolean detachIsDefault() { return false; }
        @Override public OutputStream getProcessInput() { return null; }
        void complete() { if (!isProcessTerminated()) notifyProcessTerminated(0); }
    }
    private RunnerAndConfigurationSettings application(String name, boolean compile) {
        RunManager manager = RunManager.getInstance(getProject());
        var settings = manager.createConfiguration(name, ApplicationConfigurationType.getInstance().getConfigurationFactories()[0]);
        ApplicationConfiguration configuration = (ApplicationConfiguration) settings.getConfiguration();
        configuration.setModule(getModule());
        configuration.setMainClassName("McpRunProbe");
        configuration.setBeforeRunTasks(List.of());
        configuration.setWorkingDirectory(getProject().getBasePath());
        settings.setEditBeforeRun(false);
        manager.addConfiguration(settings);
        if (compile) {
            try {
                String code = "public class McpRunProbe { public static void main(String[] args) throws Exception { System.out.println(\"MCP test service started\"); Thread.sleep(120000L); } }";
                var file = myFixture.addFileToProject("McpRunProbe.java", code);
                Path output = Files.createDirectories(Path.of(getProject().getBasePath(), "mcp-test-classes"));
                String javac = Path.of(System.getProperty("idea.mcp.test.jdk", System.getProperty("java.home")), "bin", System.getProperty("os.name").startsWith("Windows") ? "javac.exe" : "javac").toString();
                Process compilerProcess = new ProcessBuilder(javac, "-d", output.toString(), file.getVirtualFile().getPath()).redirectErrorStream(true).start();
                try {
                    assertTrue("Probe compilation timed out", compilerProcess.waitFor(30, TimeUnit.SECONDS));
                    assertEquals(new String(compilerProcess.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), 0, compilerProcess.exitValue());
                } finally { compilerProcess.destroyForcibly(); }
                assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(output));
                ModuleRootModificationUtil.updateModel(getModule(), model -> {
                    var compiler = model.getModuleExtension(CompilerModuleExtension.class);
                    compiler.inheritCompilerOutputPath(false);
                    compiler.setCompilerOutputPath(VfsUtilCore.pathToUrl(output.toString()));
                });
                IndexingTestUtil.waitUntilIndexesAreReady(getProject());
            } catch (Exception e) { throw new AssertionError(e); }
        }
        return settings;
    }
    private JsonObject restart(RunnerAndConfigurationSettings settings, String mode) {
        return service.call("restart_run_configuration", object("configurationId", settings.getUniqueID(), "mode", mode, "stopTimeoutSeconds", 10, "startTimeoutSeconds", 30));
    }
    private JsonObject await(JsonObject submitted) {
        String id = submitted.get("operationId").getAsString();
        PlatformTestUtil.waitWithEventsDispatching(() -> "Operation did not complete: " + service.call("get_run_operation", object("operationId", id)),
                () -> !service.call("get_run_operation", object("operationId", id)).get("inProgress").getAsBoolean(), 45000);
        return service.call("get_run_operation", object("operationId", id));
    }
}
