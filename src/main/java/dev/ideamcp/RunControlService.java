package dev.ideamcp;

import com.google.gson.*;
import com.intellij.execution.*;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.executors.*;
import com.intellij.execution.impl.ExecutionManagerImpl;
import com.intellij.execution.process.*;
import com.intellij.execution.runners.*;
import com.intellij.execution.target.TargetEnvironmentAwareRunProfile;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.*;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.*;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.xdebugger.XDebuggerManager;
import dev.ideamcp.protocol.Arguments;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static dev.ideamcp.protocol.Json.object;

/** Project-scoped, asynchronous control of existing local Java service configurations. EDT only. */
@Service(Service.Level.PROJECT)
public final class RunControlService implements Disposable {
    private static final Set<String> SUPPORTED_TYPES = Set.of("Application", "SpringBootApplicationConfigurationType", "MavenRunConfiguration", "GradleRunConfiguration");
    private final Project project;
    private final LinkedHashMap<String, Operation> operations = new LinkedHashMap<>();
    private final Map<String, Operation> active = new HashMap<>();
    private boolean disposed;

    public RunControlService(Project project) { this.project = project; }
    public JsonObject call(String name, JsonObject args) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        if (disposed || project.isDisposed()) throw new IllegalArgumentException("PROJECT_CLOSED");
        return switch (name) {
            case "list_run_configurations" -> list(args);
            case "restart_run_configuration" -> restart(args);
            case "get_run_operation" -> {
                Operation operation = operations.get(Arguments.required(args, "operationId"));
                if (operation == null) throw new IllegalArgumentException("OPERATION_NOT_FOUND: operations are scoped to the project and retained for the last 100 operations");
                yield operation.json();
            }
            default -> throw new IllegalArgumentException("Unknown execution tool: " + name);
        };
    }
    private JsonObject list(JsonObject args) {
        String filter = Arguments.string(args, "name", "").toLowerCase(Locale.ROOT);
        JsonArray configurations = new JsonArray();
        RunManager manager = RunManager.getInstance(project);
        for (RunnerAndConfigurationSettings settings : manager.getAllSettings()) {
            if (!settings.getName().toLowerCase(Locale.ROOT).contains(filter)) continue;
            JsonArray sessions = new JsonArray();
            for (RunContentDescriptor descriptor : running(settings)) {
                JsonObject session = process(descriptor.getProcessHandler());
                session.addProperty("executionId", descriptor.getExecutionId());
                session.add("modes", dev.ideamcp.protocol.Json.GSON.toJsonTree(modes(descriptor)));
                sessions.add(session);
            }
            String unsupported = unsupported(settings);
            Operation operation = active.get(settings.getUniqueID());
            JsonObject item = object("id", settings.getUniqueID(), "name", settings.getName(), "type", settings.getType().getId(),
                    "typeName", settings.getType().getDisplayName(), "temporary", settings.isTemporary(), "selected", settings == manager.getSelectedConfiguration(),
                    "supported", unsupported == null, "unsupportedReason", unsupported,
                    "canRun", unsupported == null && runner(settings, "run") != null, "canDebug", unsupported == null && runner(settings, "debug") != null,
                    "sessions", sessions, "activeOperationId", operation == null ? null : operation.id);
            configurations.add(item);
        }
        return object("project", project.getBasePath(), "configurations", configurations);
    }
    private JsonObject restart(JsonObject args) {
        String id = Arguments.required(args, "configurationId");
        RunnerAndConfigurationSettings settings = find(id);
        String reason = unsupported(settings);
        if (reason != null) throw new IllegalArgumentException("UNSUPPORTED_CONFIGURATION: " + reason);
        String requested = Arguments.string(args, "mode", "preserve");
        if (!Set.of("preserve", "run", "debug").contains(requested)) throw new IllegalArgumentException("mode must be preserve, run or debug");
        Operation existing = active.values().stream().filter(o -> o.configurationId.equals(id) || o.settings == settings).findFirst().orElse(null);
        if (existing != null) {
            if (!requested.equals("preserve") && !requested.equals(existing.mode))
                throw new IllegalArgumentException("OPERATION_IN_PROGRESS: poll " + existing.id + " before changing execution mode");
            return existing.json();
        }
        if (active.size() >= 10) throw new IllegalArgumentException("TOO_MANY_OPERATIONS: wait for active operations to finish");
        if (DumbService.isDumb(project)) throw new IllegalArgumentException("INDEXING: wait for indexing before restarting a service");
        List<RunContentDescriptor> descriptors = running(settings);
        Set<String> modes = new HashSet<>();
        descriptors.forEach(descriptor -> modes.addAll(modes(descriptor)));
        String mode = chooseMode(requested, modes, !descriptors.isEmpty());
        if (runner(settings, mode) == null) throw new IllegalArgumentException("EXECUTOR_UNSUPPORTED: this configuration cannot start in " + mode + " mode");
        if (isStarting(settings)) throw new IllegalArgumentException("ALREADY_STARTING: IDEA is already starting this configuration; no process was stopped");
        Operation operation = new Operation(settings, mode,
                Arguments.integer(args, "stopTimeoutSeconds", 20, 1, 120),
                Arguments.integer(args, "startTimeoutSeconds", 120, 1, 600));
        operations.put(operation.id, operation);
        active.put(id, operation);
        trim();
        // Respond with the operation id before validation/build/stop work begins.
        later(operation::begin);
        return operation.json();
    }
    static String chooseMode(String requested, Set<String> modes, boolean running) {
        if (!requested.equals("preserve")) return requested;
        if (!running) return "run";
        if (modes.size() != 1 || !Set.of("run", "debug").contains(modes.iterator().next()))
            throw new IllegalArgumentException("AMBIGUOUS_MODE: running instances have mixed/unknown modes; specify run or debug");
        return modes.iterator().next();
    }
    private RunnerAndConfigurationSettings find(String id) {
        List<RunnerAndConfigurationSettings> matches = RunManager.getInstance(project).getAllSettings().stream().filter(s -> s.getUniqueID().equals(id)).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("CONFIGURATION_NOT_FOUND_OR_AMBIGUOUS: pass an exact id from list_run_configurations");
        return matches.getFirst();
    }
    private String unsupported(RunnerAndConfigurationSettings settings) {
        if (!SUPPORTED_TYPES.contains(settings.getType().getId())) return "Supported types: local Application, Spring Boot, Maven and Gradle; compound, tests and remote attach are excluded";
        RunConfiguration configuration = settings.getConfiguration();
        if (configuration instanceof TargetEnvironmentAwareRunProfile target && target.getDefaultTargetName() != null && !target.getDefaultTargetName().isBlank())
            return "Remote execution targets are not supported";
        return null;
    }
    private List<RunContentDescriptor> running(RunnerAndConfigurationSettings settings) {
        return ExecutionManager.getInstance(project).getRunningDescriptors(candidate -> candidate == settings || candidate.getUniqueID().equals(settings.getUniqueID()))
                .stream().filter(d -> d.getProcessHandler() != null && !terminated(d.getProcessHandler())).toList();
    }
    private static boolean terminated(ProcessHandler handler) {
        return handler.isProcessTerminated() && (!(handler instanceof BaseProcessHandler<?> nativeHandler) || !nativeHandler.getProcess().isAlive());
    }
    private Set<String> modes(RunContentDescriptor descriptor) {
        Set<String> modes = new TreeSet<>();
        for (Executor executor : ExecutionManager.getInstance(project).getExecutors(descriptor)) {
            modes.add(DefaultRunExecutor.EXECUTOR_ID.equals(executor.getId()) ? "run" : DefaultDebugExecutor.EXECUTOR_ID.equals(executor.getId()) ? "debug" : executor.getId());
        }
        return modes;
    }
    private static Executor executor(String mode) { return mode.equals("debug") ? DefaultDebugExecutor.getDebugExecutorInstance() : DefaultRunExecutor.getRunExecutorInstance(); }
    private static ProgramRunner<?> runner(RunnerAndConfigurationSettings settings, String mode) {
        Executor executor = executor(mode);
        return executor == null ? null : ProgramRunner.getRunner(executor.getId(), settings.getConfiguration());
    }
    private boolean isStarting(RunnerAndConfigurationSettings settings) {
        for (String mode : new String[]{"run", "debug"}) {
            if (runner(settings, mode) == null) continue;
            var builder = ExecutionEnvironmentBuilder.createOrNull(executor(mode), settings);
            if (builder != null && ExecutionManager.getInstance(project).isStarting(builder.target(DefaultExecutionTarget.INSTANCE).build())) return true;
        }
        return false;
    }
    private void later(Runnable action) {
        ApplicationManager.getApplication().invokeLater(() -> { if (!disposed && !project.isDisposed()) action.run(); }, ModalityState.any());
    }
    private static JsonObject process(ProcessHandler handler) {
        if (handler == null) return object("status", "not_started");
        Long pid = null;
        if (handler instanceof BaseProcessHandler<?> nativeHandler) {
            try { pid = nativeHandler.getProcess().pid(); } catch (UnsupportedOperationException ignored) {}
        }
        return object("status", terminated(handler) ? "terminated" : handler.isProcessTerminating() || handler.isProcessTerminated() ? "terminating" : "running", "pid", pid, "exitCode", handler.getExitCode());
    }
    private void trim() {
        Iterator<Operation> iterator = operations.values().iterator();
        while (operations.size() > 100 && iterator.hasNext()) {
            Operation operation = iterator.next();
            if (active.containsValue(operation)) continue;
            operation.cleanup();
            iterator.remove();
        }
    }
    @Override public void dispose() {
        disposed = true;
        for (Operation operation : operations.values()) operation.cleanup();
        operations.clear(); active.clear();
    }
    private final class Operation {
        final String id = UUID.randomUUID().toString();
        final RunnerAndConfigurationSettings settings;
        final String configurationId;
        final String mode;
        final int stopTimeout, startTimeout;
        final String created = Instant.now().toString();
        final Disposable lifetime = Disposer.newDisposable("Java MCP run operation");
        String phase = "queued", error;
        long deadline;
        List<ProcessHandler> previous = List.of();
        ProcessHandler current;
        ExecutionEnvironment environment;
        ScheduledFuture<?> timer;
        boolean cleaned;
        Operation(RunnerAndConfigurationSettings settings, String mode, int stopTimeout, int startTimeout) {
            this.settings = settings; this.mode = mode; this.stopTimeout = stopTimeout; this.startTimeout = startTimeout;
            configurationId = settings.getUniqueID();
        }
        void begin() {
            if (!live()) return;
            phase = "checking";
            try {
                if (!RunManager.getInstance(project).hasSettings(settings) || !configurationId.equals(settings.getUniqueID())) throw new IllegalArgumentException("CONFIGURATION_REMOVED_OR_RENAMED");
                if (DumbService.isDumb(project)) throw new IllegalArgumentException("INDEXING: no process was stopped");
                if (unsupported(settings) != null) throw new IllegalArgumentException("Configuration changed to an unsupported target");
                if (settings.isEditBeforeRun()) throw new IllegalArgumentException("EDIT_BEFORE_RUN: disable Edit configuration before launch in IDEA before using MCP");
                settings.checkSettings(executor(mode));
                if (!DefaultExecutionTarget.INSTANCE.canRun(settings.getConfiguration())) throw new IllegalArgumentException("Configuration cannot execute on the local target");
                if (isStarting(settings)) throw new IllegalArgumentException("ALREADY_STARTING: no process was stopped");
                previous = running(settings).stream().map(RunContentDescriptor::getProcessHandler).distinct().toList();
                phase = "stopping";
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(stopTimeout);
                for (ProcessHandler handler : previous) {
                    if (!handler.isProcessTerminating() && !handler.isProcessTerminated()) ExecutionManagerImpl.stopProcess(handler);
                }
                tick();
                if (live()) timer = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(() -> later(this::tick), 100, 100, TimeUnit.MILLISECONDS);
            } catch (Exception e) { fail(e.getMessage()); }
        }
        boolean live() { return !cleaned && !disposed && !project.isDisposed() && active.get(configurationId) == this; }
        void tick() {
            if (!live()) return;
            if (phase.equals("stopping")) {
                if (previous.stream().allMatch(RunControlService::terminated)) launch();
                else if (System.nanoTime() >= deadline) fail("STOP_TIMEOUT: old process did not exit; replacement was not started");
            } else if ((phase.equals("starting") || phase.equals("building")) && System.nanoTime() >= deadline) {
                phase = "start_timeout";
                error = "START_TIMEOUT: IDEA may still be building/starting. Continue polling this operation; do not submit another restart.";
            }
        }
        void launch() {
            try {
                if (!running(settings).isEmpty() || isStarting(settings)) throw new IllegalArgumentException("CONCURRENT_START: a new instance appeared while stopping; replacement was not started");
                if (!RunManager.getInstance(project).hasSettings(settings) || !configurationId.equals(settings.getUniqueID()) || unsupported(settings) != null) throw new IllegalArgumentException("Configuration was removed or changed while stopping");
                environment = ExecutionEnvironmentBuilder.create(executor(mode), settings).target(DefaultExecutionTarget.INSTANCE).build();
                environment.assignNewExecutionId();
                project.getMessageBus().connect(lifetime).subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionListener() {
                    @Override public void processStarting(String executorId, ExecutionEnvironment env) {
                        later(() -> { if (matches(env) && live() && !phase.equals("start_timeout")) phase = "starting"; });
                    }
                    @Override public void processNotStarted(String executorId, ExecutionEnvironment env) {
                        later(() -> { if (matches(env) && live()) fail("START_FAILED: IDEA cancelled or could not start the configuration; inspect its Run/Build window"); });
                    }
                    @Override public void processNotStarted(String executorId, ExecutionEnvironment env, Throwable cause) {
                        later(() -> { if (matches(env) && live()) fail("START_FAILED: " + (cause == null ? "unknown error" : cause.getMessage())); });
                    }
                    @Override public void processStarted(String executorId, ExecutionEnvironment env, ProcessHandler handler) {
                        later(() -> {
                            if (!matches(env) || !live()) return;
                            current = handler;
                            phase = handler.isProcessTerminated() ? "terminated" : "started";
                            error = null;
                            active.remove(configurationId, Operation.this);
                            if (timer != null) timer.cancel(false);
                            handler.addProcessListener(new ProcessAdapter() {
                                @Override public void processTerminated(ProcessEvent event) { later(() -> { if (!cleaned) { phase = "terminated"; cleanup(); } }); }
                            }, lifetime);
                            if (handler.isProcessTerminated()) cleanup();
                        });
                    }
                });
                phase = "building";
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(startTimeout);
                ProgramRunnerUtil.executeConfiguration(environment, false, true);
            } catch (Exception e) { fail("START_FAILED: " + e.getMessage()); }
        }
        boolean matches(ExecutionEnvironment other) { return other == environment; }
        void fail(String message) {
            if (!live()) return;
            error = message == null ? "Unknown execution error" : message;
            phase = "failed";
            active.remove(configurationId, this);
            cleanup();
        }
        void cleanup() {
            if (cleaned) return;
            cleaned = true;
            if (timer != null) timer.cancel(false);
            Disposer.dispose(lifetime);
        }
        JsonObject json() {
            boolean attached = current != null && !current.isProcessTerminated() && Arrays.stream(XDebuggerManager.getInstance(project).getDebugSessions())
                    .anyMatch(session -> session.getRunContentDescriptor().getProcessHandler() == current);
            return object("operationId", id, "project", project.getBasePath(), "configurationId", configurationId, "configurationName", settings.getName(),
                    "mode", mode, "phase", phase, "inProgress", active.get(configurationId) == this, "createdAt", created,
                    "previousProcessCount", previous.size(), "stoppedProcessCount", previous.stream().filter(RunControlService::terminated).count(),
                    "executionId", environment == null ? null : environment.getExecutionId(), "process", process(current),
                    "debuggerAttached", attached, "error", error,
                    "note", "started means IDEA reported process start, not Spring Boot health readiness; debug mode uses existing IDEA breakpoints");
        }
    }
}
