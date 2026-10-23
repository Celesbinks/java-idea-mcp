package dev.ideamcp;

import com.google.gson.*;
import com.intellij.execution.*;
import com.intellij.execution.impl.ExecutionManagerImpl;
import com.intellij.execution.process.*;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import dev.ideamcp.protocol.Arguments;
import java.time.Instant;
import java.util.*;
import static dev.ideamcp.protocol.Json.object;

/** Project-local bounded capture. Disposing this service detaches listeners without stopping processes. */
@Service(Service.Level.PROJECT)
public final class RunLogService implements Disposable {
    private static final int MAX_SESSIONS = 32;
    private final Project project;
    private final LinkedHashMap<String, Session> sessions = new LinkedHashMap<>();
    private boolean disposed;
    public RunLogService(Project project) {
        this.project = project;
        project.getMessageBus().connect(this).subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionListener() {
            @Override public void processStarting(String executor, ExecutionEnvironment env, ProcessHandler handler) { capture(env, handler); }
            @Override public void processStarted(String executor, ExecutionEnvironment env, ProcessHandler handler) { capture(env, handler); }
        });
    }
    private void capture(ExecutionEnvironment env, ProcessHandler handler) {
        RunnerAndConfigurationSettings settings = env.getRunnerAndConfigurationSettings();
        if (settings != null && env.getProject() == project) attach(settings.getUniqueID(), settings.getName(), handler, env.getExecutionId());
    }
    synchronized String attach(String configurationId, String name, ProcessHandler handler, long executionId) {
        if (disposed || handler == null) return null;
        for (Session session : sessions.values()) if (session.handler == handler) return session.id;
        while (sessions.size() >= MAX_SESSIONS) {
            Session oldest = sessions.values().stream().filter(s -> s.handler.isProcessTerminated()).findFirst().orElse(null);
            if (oldest == null) return null; // Do not evict a live instance silently.
            oldest.detach(); sessions.remove(oldest.id);
        }
        Session session = new Session(configurationId, name, handler, executionId);
        sessions.put(session.id, session);
        handler.addProcessListener(session.listener);
        return session.id;
    }
    void discover() {
        ApplicationManager.getApplication().assertIsDispatchThread();
        for (RunnerAndConfigurationSettings settings : RunManager.getInstance(project).getAllSettings()) {
            for (RunContentDescriptor descriptor : ExecutionManager.getInstance(project).getRunningDescriptors(s -> s == settings || s.getUniqueID().equals(settings.getUniqueID()))) {
                ProcessHandler handler = descriptor.getProcessHandler();
                if (handler != null && !handler.isProcessTerminated()) attach(settings.getUniqueID(), settings.getName(), handler, descriptor.getExecutionId());
            }
        }
    }
    JsonObject read(JsonObject args) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        discover();
        return snapshot(args);
    }
    synchronized JsonObject snapshot(JsonObject args) {
        if (disposed || project.isDisposed()) throw new IllegalArgumentException("PROJECT_CLOSED");
        String configurationId = Arguments.required(args, "configurationId"), id = Arguments.string(args, "sessionId", null);
        String cursor = Arguments.string(args, "cursor", null);
        int maxChars = Arguments.integer(args, "maxChars", 8000, 2, 20000);
        List<Session> matches = sessions.values().stream().filter(s -> s.configurationId.equals(configurationId) && (id == null || s.id.equals(id))).toList();
        if (matches.isEmpty()) throw new IllegalArgumentException("LOG_SESSION_NOT_FOUND: list run configurations for logSessionId; only output captured since tracking began is available");
        if (matches.size() != 1) throw new IllegalArgumentException("LOG_SESSION_REQUIRED: multiple retained instances; pass an exact logSessionId as sessionId");
        Session session = matches.getFirst();
        JsonObject result = session.buffer.read(cursor, maxChars);
        result.addProperty("sessionId", session.id); result.addProperty("configurationId", session.configurationId);
        result.addProperty("configurationName", session.name); result.addProperty("executionId", session.executionId);
        result.addProperty("captureStartedAt", session.started); result.addProperty("terminated", session.handler.isProcessTerminated());
        result.addProperty("historyBeforeCaptureAvailable", false);
        result.addProperty("note", "stdout/stderr captured since listener attachment; system/command-line output excluded; up to 262144 UTF-16 units and 2048 chunks per session, 32 sessions per project; old terminated sessions may be evicted; log content may contain application secrets");
        return result;
    }
    synchronized JsonArray retained() {
        JsonArray result = new JsonArray();
        for (Session s : sessions.values()) result.add(object("sessionId", s.id, "configurationId", s.configurationId, "configurationName", s.name,
                "executionId", s.executionId, "terminated", s.handler.isProcessTerminated(), "captureStartedAt", s.started));
        return result;
    }
    @Override public synchronized void dispose() { disposed = true; sessions.values().forEach(Session::detach); sessions.clear(); }
    private static final class Session {
        final String id = UUID.randomUUID().toString(), configurationId, name, started = Instant.now().toString();
        final ProcessHandler handler;
        final long executionId;
        final RunLogBuffer buffer = new RunLogBuffer(262144, 2048);
        final ProcessAdapter listener = new ProcessAdapter() {
            @Override public void onTextAvailable(ProcessEvent event, Key outputType) {
                if (ProcessOutputType.isStdout(outputType)) buffer.append("stdout", event.getText());
                else if (ProcessOutputType.isStderr(outputType)) buffer.append("stderr", event.getText());
            }
        };
        Session(String configurationId, String name, ProcessHandler handler, long executionId) { this.configurationId = configurationId; this.name = name; this.handler = handler; this.executionId = executionId; }
        void detach() { handler.removeProcessListener(listener); }
    }
}
