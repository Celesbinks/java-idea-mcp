package dev.ideamcp;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.ServiceContainerUtil;
import dev.ideamcp.protocol.QueryTiming;
import git4idea.commands.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Independent-sandbox-only hooks. No command text, environment or output is collected. */
final class GitCommandProbe {
    @jdk.jfr.Name("dev.ideamcp.test.GitCommand")
    @jdk.jfr.StackTrace(false)
    @jdk.jfr.Enabled(false)
    public static final class CommandEvent extends jdk.jfr.Event {
        public long requestId;
        public boolean statusCommand;
    }
    record Counts(int status, int other, int failed) {}
    private static final class MutableCounts {
        final AtomicInteger status = new AtomicInteger(), other = new AtomicInteger(), failed = new AtomicInteger();
    }
    private final ConcurrentHashMap<Long, MutableCounts> counts = new ConcurrentHashMap<>();
    private final AtomicLong lastRequestId = new AtomicLong();
    long lastRequestId() { return lastRequestId.get(); }
    static GitCommandProbe install(Project project, Disposable parent) throws Exception {
        GitCommandProbe probe = new GitCommandProbe();
        Git original = Git.getInstance();
        Method command = GitHandler.class.getDeclaredMethod("getCommand"); command.setAccessible(true);
        Git proxy = (Git) Proxy.newProxyInstance(Git.class.getClassLoader(), new Class<?>[]{Git.class}, (unused, method, arguments) -> {
            QueryTiming.Context context = QueryTiming.current();
            if (!method.getName().equals("runCommand") || context == null || arguments == null || arguments.length != 1)
                return invoke(original, method, arguments);
            long entered = System.nanoTime(), cpu = cpu();
            CommandEvent event = new CommandEvent(); event.requestId = context.id(); event.begin();
            Trace[] trace = new Trace[1];
            Object[] wrapped = arguments;
            if (arguments[0] instanceof com.intellij.openapi.util.Computable<?> factory) {
                wrapped = new Object[]{(com.intellij.openapi.util.Computable<GitLineHandler>) () -> {
                    try (var phase = QueryTiming.phase(context, "git_handler")) {
                        GitLineHandler handler = (GitLineHandler) factory.compute();
                        trace[0] = probe.observe(project, handler, command, context, entered); return handler;
                    }
                }};
            } else if (arguments[0] instanceof GitLineHandler handler)
                trace[0] = probe.observe(project, handler, command, context, entered);
            try { return invoke(original, method, wrapped); }
            finally {
                long ended = System.nanoTime();
                QueryTiming.record(context, "git_command_total", ended - entered);
                if (trace[0] != null) trace[0].returned(ended);
                long endedCpu = cpu();
                if (cpu >= 0 && endedCpu >= cpu) QueryTiming.record(context, "git_calling_thread_cpu", endedCpu - cpu);
                event.statusCommand = trace[0] != null; event.end(); event.commit();
            }
        });
        ServiceContainerUtil.replaceService(ApplicationManager.getApplication(), Git.class, proxy, parent);
        return probe;
    }
    Counts counts(long requestId) {
        MutableCounts value = counts.remove(requestId);
        return value == null ? new Counts(0, 0, 0) : new Counts(value.status.get(), value.other.get(), value.failed.get());
    }
    private Trace observe(Project project, GitLineHandler handler, Method command, QueryTiming.Context context, long entered) {
        if (handler.project() != project) return null;
        lastRequestId.set(context.id());
        MutableCounts count = counts.computeIfAbsent(context.id(), unused -> new MutableCounts());
        try {
            if (command.invoke(handler) != GitCommand.STATUS) { count.other.incrementAndGet(); return null; }
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        count.status.incrementAndGet();
        Trace trace = new Trace(context, entered, count);
        handler.addLineListener(trace); return trace;
    }
    private static final class Trace implements GitLineHandlerListener {
        final QueryTiming.Context context;
        final long entered;
        final MutableCounts count;
        final AtomicLong started = new AtomicLong(), terminated = new AtomicLong();
        Trace(QueryTiming.Context context, long entered, MutableCounts count) { this.context = context; this.entered = entered; this.count = count; }
        @Override public void onLineAvailable(String line, com.intellij.openapi.util.Key outputType) { }
        @Override public void processStarted() {
            long now = System.nanoTime();
            if (started.compareAndSet(0, now)) QueryTiming.record(context, "git_before_process", now - entered);
        }
        @Override public void processTerminated(int exitCode) {
            long now = System.nanoTime();
            if (terminated.compareAndSet(0, now) && started.get() != 0)
                QueryTiming.record(context, "git_process", now - started.get());
        }
        @Override public void startFailed(Throwable failure) { count.failed.incrementAndGet(); terminated.compareAndSet(0, System.nanoTime()); }
        void returned(long returned) {
            long end = terminated.get();
            if (end != 0) QueryTiming.record(context, "git_after_process", returned - end);
        }
    }
    private static long cpu() { var bean = ManagementFactory.getThreadMXBean(); return bean.isCurrentThreadCpuTimeSupported() ? bean.getCurrentThreadCpuTime() : -1; }
    private static Object invoke(Git original, Method method, Object[] args) throws Throwable {
        try { return method.invoke(original, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
