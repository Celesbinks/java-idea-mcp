package dev.ideamcp.protocol;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Opt-in request-local durations. Never stores arguments, credentials, paths or output. */
public final class QueryTiming {
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private static final Span NONE = new Span(null, null);
    private static final AtomicLong IDS = new AtomicLong();

    private QueryTiming() {}
    public static Context current() { return CURRENT.get(); }
    /** Explicit propagation hook; the caller must restore the returned previous context. */
    public static Context attach(Context context) {
        Context previous = CURRENT.get();
        if (context == null) CURRENT.remove(); else CURRENT.set(context);
        return previous;
    }
    public static Scope open(boolean enabled) { return new Scope(enabled ? new Context() : null); }
    public static Span phase(String name) { return phase(current(), name); }
    public static Span phase(Context context, String name) { return context == null ? NONE : new Span(context, name); }
    /** Numeric observations only; callers must use fixed names, never request data. */
    public static void record(Context context, String name, long nanos) {
        if (context == null) return;
        validateName(name);
        if (nanos < 0) throw new IllegalArgumentException("Negative timing duration");
        context.add(name, nanos);
    }
    private static void validateName(String name) {
        if (name == null || name.length() > 48 || !name.matches("[a-z_]+"))
            throw new IllegalArgumentException("Invalid timing phase");
    }

    public static final class Context {
        private final long id = IDS.incrementAndGet();
        private final ConcurrentHashMap<String, LongAdder> nanos = new ConcurrentHashMap<>();
        private boolean responseWritten;
        public long id() { return id; }
        /** -1 means unobserved, including phases skipped by an error response. */
        public long nanos(String name) { var value = nanos.get(name); return value == null ? -1 : value.sum(); }
        void responseWritten() { responseWritten = true; }
        boolean wasResponseWritten() { return responseWritten; }
        private void add(String name, long elapsed) { nanos.computeIfAbsent(name, unused -> new LongAdder()).add(elapsed); }
        public Map<String, Long> snapshot() {
            Map<String, Long> result = new TreeMap<>(); nanos.forEach((name, value) -> result.put(name, value.sum())); return result;
        }
        public String header() {
            StringBuilder result = new StringBuilder();
            snapshot().forEach((name, ns) -> {
                if (!result.isEmpty()) result.append(',');
                long us = ns / 1000;
                result.append(name).append(";dur=").append(us / 1000).append('.');
                long fraction = us % 1000;
                if (fraction < 100) result.append('0'); if (fraction < 10) result.append('0');
                result.append(fraction);
            });
            return result.toString();
        }
    }
    public static final class Span implements AutoCloseable {
        private final Context context;
        private final String name;
        private final long start;
        private final AtomicBoolean closed;
        private Span(Context context, String name) {
            if (context != null) validateName(name);
            this.context = context; this.name = name;
            start = context == null ? 0 : System.nanoTime(); closed = context == null ? null : new AtomicBoolean();
        }
        @Override public void close() {
            if (context != null && closed.compareAndSet(false, true)) context.add(name, System.nanoTime() - start);
        }
    }
    public static final class Scope implements AutoCloseable {
        private final Context previous;
        private Scope(Context next) { previous = attach(next); }
        @Override public void close() { attach(previous); }
    }
}
