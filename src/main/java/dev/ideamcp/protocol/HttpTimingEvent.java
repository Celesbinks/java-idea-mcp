package dev.ideamcp.protocol;

import jdk.jfr.*;

/** Optional recording sink. Fields deliberately cannot contain request or user data. */
@Name(HttpTimingEvent.NAME)
@Label("Java MCP HTTP completion")
@Category("Java Symbol MCP")
@StackTrace(false)
@Enabled(false)
public final class HttpTimingEvent extends Event {
    public static final String NAME = "dev.ideamcp.HttpRequestCompleted";
    public long requestId;
    public boolean responseCompleted;
    @Timespan(Timespan.NANOSECONDS) public long executorWait;
    @Timespan(Timespan.NANOSECONDS) public long taskToHandler;
    @Timespan(Timespan.NANOSECONDS) public long admission;
    @Timespan(Timespan.NANOSECONDS) public long read;
    @Timespan(Timespan.NANOSECONDS) public long parse;
    @Timespan(Timespan.NANOSECONDS) public long rpc;
    @Timespan(Timespan.NANOSECONDS) public long provider;
    @Timespan(Timespan.NANOSECONDS) public long serialize;
    @Timespan(Timespan.NANOSECONDS) public long headersPrepare;
    @Timespan(Timespan.NANOSECONDS) public long headersSend;
    @Timespan(Timespan.NANOSECONDS) public long bodyWrite;
    @Timespan(Timespan.NANOSECONDS) public long exchangeClose;
    @Timespan(Timespan.NANOSECONDS) public long handlerThroughClose;
    @Timespan(Timespan.NANOSECONDS) public long taskThroughClose;

    static void publish(QueryTiming.Context context, long handlerStart, long taskQueued, boolean closed) {
        if (context == null) return;
        var event = new HttpTimingEvent();
        if (!event.isEnabled()) return;
        long end = System.nanoTime();
        event.requestId = context.id(); event.responseCompleted = closed && context.wasResponseWritten();
        event.executorWait = context.nanos("http_executor_wait");
        event.taskToHandler = context.nanos("http_task_to_handler");
        event.admission = context.nanos("http_admission"); event.read = context.nanos("http_read");
        event.parse = context.nanos("http_parse"); event.rpc = context.nanos("rpc_total");
        event.provider = context.nanos("adapter_provider_total"); event.serialize = context.nanos("http_serialize");
        event.headersPrepare = context.nanos("http_headers_prepare"); event.headersSend = context.nanos("http_send_headers");
        event.bodyWrite = context.nanos("http_write_body"); event.exchangeClose = context.nanos("http_close");
        event.handlerThroughClose = end - handlerStart;
        event.taskThroughClose = taskQueued == 0 ? -1 : end - taskQueued;
        event.commit();
    }
}
