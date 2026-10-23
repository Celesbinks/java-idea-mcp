package dev.ideamcp;

import com.google.gson.*;
import com.intellij.debugger.engine.JavaDebugProcess;
import com.intellij.debugger.engine.SuspendContextImpl;
import com.intellij.debugger.engine.events.SuspendContextCommandImpl;
import com.intellij.debugger.ui.breakpoints.JavaLineBreakpointType;
import com.intellij.execution.RunManager;
import com.intellij.execution.target.TargetEnvironmentAwareRunProfile;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.*;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.*;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.xdebugger.*;
import com.intellij.xdebugger.breakpoints.*;
import com.sun.jdi.*;
import dev.ideamcp.protocol.Arguments;
import java.util.*;
import java.util.concurrent.*;
import static dev.ideamcp.protocol.Json.object;

/** EDT session/control coordination; suspended Java VM reads run on IDEA's debugger manager thread. */
@Service(Service.Level.PROJECT)
public final class DebugService implements Disposable {
    static final Set<String> NAMES = Set.of("list_debug_sessions", "read_debug_threads", "read_debug_stack", "read_debug_variables", "control_debugger", "list_breakpoints", "set_breakpoint", "remove_breakpoint");
    private static final Set<String> TYPES = Set.of("Application", "SpringBootApplicationConfigurationType", "MavenRunConfiguration", "GradleRunConfiguration");
    private final Project project;
    private final Map<XDebugSession, Entry> sessions = new IdentityHashMap<>();
    private final Map<XLineBreakpoint<?>, String> breakpoints = new IdentityHashMap<>();
    private volatile boolean disposed;
    public DebugService(Project project) { this.project = project; }

    static JsonObject invoke(String name, JsonObject args) throws Exception {
        var app = ApplicationManager.getApplication();
        if (app.isDispatchThread()) throw new IllegalArgumentException("BACKGROUND_REQUIRED: invoke debugger tools from a worker thread");
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        app.invokeLater(() -> {
            if (result.isDone()) return;
            try {
                Project project = JavaCodeTools.selectProject(Arguments.string(args, "project", null));
                project.getService(DebugService.class).request(name, args, result);
            } catch (Exception e) { result.completeExceptionally(e); }
        });
        try { return result.get(20, TimeUnit.SECONDS); }
        catch (TimeoutException e) { result.cancel(false); throw new IllegalArgumentException("DEBUG_TIMEOUT: inspect list_debug_sessions before retrying any control command"); }
        catch (InterruptedException e) { result.cancel(false); Thread.currentThread().interrupt(); throw e; }
        catch (ExecutionException e) { if (e.getCause() instanceof Exception cause) throw cause; throw e; }
    }
    void request(String name, JsonObject args, CompletableFuture<JsonObject> result) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        if (disposed || project.isDisposed()) throw new IllegalArgumentException("PROJECT_CLOSED");
        if (name.equals("list_breakpoints") || name.equals("set_breakpoint") || name.equals("remove_breakpoint")) {
            result.complete(breakpointRequest(name,args)); return;
        }
        discover();
        if (name.equals("list_debug_sessions")) {
            JsonArray items = new JsonArray();
            sessions.values().stream().sorted(Comparator.comparing(e -> e.id)).forEach(e -> items.add(e.json()));
            result.complete(object("project",project.getBasePath(),"sessions",items)); return;
        }
        Entry entry = sessions.values().stream().filter(e -> e.id.equals(Arguments.required(args,"sessionId"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("DEBUG_SESSION_NOT_FOUND: use an exact ID from list_debug_sessions in this project"));
        entry.refresh();
        if (name.equals("control_debugger")) { result.complete(control(entry,args)); return; }
        String pauseId = Arguments.required(args,"pauseId");
        SuspendContextImpl context = entry.requirePause(pauseId);
        context.getManagerThread().invoke(new SuspendContextCommandImpl(context) {
            @Override public void contextAction(SuspendContextImpl current) {
                if (result.isDone()) return;
                try {
                    entry.requirePause(pauseId);
                    if (disposed || project.isDisposed()) throw new IllegalArgumentException("PROJECT_CLOSED");
                    JsonObject data = switch (name) {
                        case "read_debug_threads" -> threads(current,args);
                        case "read_debug_stack" -> stack(current,args);
                        case "read_debug_variables" -> variables(current,args);
                        default -> throw new IllegalArgumentException("Unknown debugger tool");
                    };
                    entry.requirePause(pauseId);
                    data.addProperty("sessionId",entry.id); data.addProperty("pauseId",pauseId);
                    result.complete(data);
                } catch (Exception e) { result.completeExceptionally(readFailure(e)); }
            }
            @Override protected void commandCancelled() { result.completeExceptionally(new IllegalArgumentException("STALE_PAUSE: debugger resumed or disconnected; refresh sessions")); }
        });
    }
    private static Exception readFailure(Exception e) {
        if (e instanceof VMDisconnectedException) return new IllegalArgumentException("DEBUG_DISCONNECTED");
        if (e instanceof InvalidStackFrameException || e instanceof IncompatibleThreadStateException) return new IllegalArgumentException("STALE_PAUSE: thread/frame is no longer suspended");
        if (e instanceof ObjectCollectedException) return new IllegalArgumentException("DEBUG_OBJECT_COLLECTED: refresh the suspended snapshot");
        return e;
    }
    private void discover() {
        Set<XDebugSession> live = Collections.newSetFromMap(new IdentityHashMap<>());
        for (XDebugSession session : XDebuggerManager.getInstance(project).getDebugSessions()) {
            if (session.isStopped() || !(session.getDebugProcess() instanceof JavaDebugProcess)) continue;
            var env = session.getExecutionEnvironment();
            var settings = env == null ? null : env.getRunnerAndConfigurationSettings();
            if (env == null || env.getProject() != project || settings == null || !TYPES.contains(settings.getType().getId())) continue;
            if (settings.getConfiguration() instanceof TargetEnvironmentAwareRunProfile target && target.getDefaultTargetName() != null && !target.getDefaultTargetName().isBlank()) continue;
            live.add(session);
            Entry entry = sessions.computeIfAbsent(session,s -> new Entry(s,settings.getUniqueID()));
            entry.refresh();
        }
        Iterator<Entry> it = sessions.values().iterator();
        while (it.hasNext()) { Entry entry=it.next();if(!live.contains(entry.session)){entry.detach();it.remove();} }
    }
    private JsonObject control(Entry entry, JsonObject args) {
        String action=Arguments.required(args,"action");
        if(!Set.of("pause","resume","step_into","step_over","step_out").contains(action))throw new IllegalArgumentException("Invalid debugger action");
        if(entry.pendingAction!=null)throw new IllegalArgumentException("DEBUG_CONTROL_PENDING: poll list_debug_sessions before another command");
        if(action.equals("pause")) {
            if(entry.session.isSuspended())throw new IllegalArgumentException("ALREADY_SUSPENDED: use the current pauseId");
            if(args.has("pauseId"))throw new IllegalArgumentException("pauseId must be omitted for pause");
        } else entry.requirePause(Arguments.required(args,"pauseId"));
        String commandId=UUID.randomUUID().toString();
        entry.pendingAction=action;entry.commandId=commandId;entry.pauseId=null;
        switch(action) {
            case "pause" -> entry.session.pause();
            case "resume" -> entry.session.resume();
            case "step_into" -> entry.session.stepInto();
            case "step_over" -> entry.session.stepOver(false);
            case "step_out" -> entry.session.stepOut();
        }
        return object("sessionId",entry.id,"commandId",commandId,"action",action,"accepted",true,
                "note","Command submitted to IDEA; poll list_debug_sessions for the resulting state and new pauseId. Acceptance does not mean a step or pause completed.");
    }
    private final class Entry {
        final String id=UUID.randomUUID().toString(),configurationId;
        final XDebugSession session;
        volatile SuspendContextImpl context;
        volatile String pauseId;
        String pendingAction,commandId;
        final XDebugSessionListener listener=new XDebugSessionListener(){
            @Override public void beforeSessionResume(){pauseId=null;}
            @Override public void sessionResumed(){pauseId=null;}
            @Override public void sessionStopped(){pauseId=null;}
        };
        Entry(XDebugSession session,String configurationId){this.session=session;this.configurationId=configurationId;session.addSessionListener(listener);}
        void refresh(){
            if(!session.isSuspended()){context=null;pauseId=null;if(!"pause".equals(pendingAction))pendingAction=null;return;}
            if(session.getSuspendContext() instanceof SuspendContextImpl current && current!=context){context=current;pauseId=UUID.randomUUID().toString();pendingAction=null;}
        }
        SuspendContextImpl requirePause(String expected){
            SuspendContextImpl snapshot=context;
            if(pauseId==null||snapshot==null||snapshot.isResumed()||!pauseId.equals(expected))throw new IllegalArgumentException("STALE_PAUSE: refresh list_debug_sessions and use its current pauseId");
            return snapshot;
        }
        JsonObject json(){return object("sessionId",id,"configurationId",configurationId,"name",session.getSessionName(),"executionId",session.getRunContentDescriptor().getExecutionId(),
                "state",pendingAction!=null?"command_pending":session.isSuspended()?"suspended":"running","debuggerAttached",((JavaDebugProcess)session.getDebugProcess()).getDebuggerSession().isAttached(),
                "pauseId",pauseId,"pendingAction",pendingAction,"commandId",commandId,"breakpointsMuted",session.areBreakpointsMuted());}
        void detach(){pauseId=null;session.removeSessionListener(listener);}
    }
    private static JsonObject threads(SuspendContextImpl context, JsonObject args) {
        List<ThreadReference> all=new ArrayList<>(context.getVirtualMachineProxy().getVirtualMachine().allThreads());
        all.sort(Comparator.comparingLong(ThreadReference::uniqueID));
        JsonArray items=new JsonArray();int offset=offset(args),limit=limit(args);
        for(ThreadReference thread:all.subList(Math.min(offset,all.size()),Math.min(all.size(),offset+limit)))
            items.add(object("threadId",Long.toString(thread.uniqueID()),"name",thread.name(),"status",thread.status(),"suspended",thread.isSuspended(),"atBreakpoint",thread.isAtBreakpoint()));
        JsonObject result=page("threads",items,all.size(),args);
        result.addProperty("eventThreadId",context.getThread()==null?null:Long.toString(context.getThread().getThreadReference().uniqueID()));return result;
    }
    private static ThreadReference thread(SuspendContextImpl context,JsonObject args) {
        String id=Arguments.required(args,"threadId");
        ThreadReference thread=context.getVirtualMachineProxy().getVirtualMachine().allThreads().stream().filter(t->Long.toString(t.uniqueID()).equals(id)).findFirst()
                .orElseThrow(()->new IllegalArgumentException("THREAD_NOT_FOUND: refresh read_debug_threads"));
        if(!thread.isSuspended())throw new IllegalArgumentException("THREAD_NOT_SUSPENDED: this thread is not suspended by the current debugger state");
        return thread;
    }
    private static JsonObject stack(SuspendContextImpl context,JsonObject args) throws IncompatibleThreadStateException {
        ThreadReference thread=thread(context,args);int total=thread.frameCount(),start=Math.min(offset(args),total),count=Math.min(limit(args),total-start);
        JsonArray frames=new JsonArray();int index=start;
        for(StackFrame frame:count==0?List.<StackFrame>of():thread.frames(start,count)) {
            Location loc=frame.location();String source=null,path=null;
            try{source=loc.sourceName();path=loc.sourcePath();}catch(AbsentInformationException ignored){}
            frames.add(object("frameIndex",index++,"className",loc.declaringType().name(),"method",loc.method().name(),"signature",loc.method().signature(),
                    "sourceName",source,"sourcePath",path,"line",loc.lineNumber()>0?loc.lineNumber():null,"native",loc.method().isNative()));
        }
        JsonObject result=page("frames",frames,total,args);result.addProperty("threadId",Long.toString(thread.uniqueID()));return result;
    }
    private static JsonObject variables(SuspendContextImpl context,JsonObject args) throws IncompatibleThreadStateException {
        ThreadReference thread=thread(context,args);int index=Arguments.integer(args,"frameIndex",0,0,1000000);
        if(index>=thread.frameCount())throw new IllegalArgumentException("FRAME_NOT_FOUND: use an absolute frameIndex from read_debug_stack");
        JsonObject result=frameVariables(thread.frame(index),args);result.addProperty("threadId",Long.toString(thread.uniqueID()));result.addProperty("frameIndex",index);return result;
    }
    static JsonObject frameVariables(StackFrame frame,JsonObject args){
        List<JsonObject> values=new ArrayList<>();boolean available=true;
        if(frame.thisObject()!=null)values.add(object("name","this","argument",false,"value",value(frame.thisObject(),args)));
        try {
            List<LocalVariable> locals=new ArrayList<>(frame.visibleVariables());locals.sort(Comparator.comparing(LocalVariable::name).thenComparing(LocalVariable::signature));
            int start=Math.max(0,offset(args)-values.size()),end=Math.min(locals.size(),offset(args)+limit(args)-values.size());
            // Fetch only requested local values; page metadata still counts every visible local.
            Map<LocalVariable,Value> read=frame.getValues(locals.subList(Math.min(start,locals.size()),Math.max(Math.min(start,locals.size()),end)));
            for(LocalVariable local:locals)values.add(object("name",local.name(),"type",local.typeName(),"argument",local.isArgument(),"value",read.containsKey(local)?value(read.get(local),args):null));
        }catch(AbsentInformationException e){available=false;int n=0;for(Value arg:frame.getArgumentValues())values.add(object("name","arg"+n++,"argument",true,"value",value(arg,args)));}
        JsonArray items=new JsonArray();values.stream().skip(offset(args)).limit(limit(args)).forEach(items::add);
        JsonObject result=page("variables",items,values.size(),args);
        result.addProperty("localInformationAvailable",available);result.addProperty("rendering","JDI values only; no evaluation, getters, toString, object expansion or writes");return result;
    }
    static JsonObject value(Value value,JsonObject args){
        if(value==null)return object("kind","null","value",JsonNull.INSTANCE);
        if(value instanceof PrimitiveValue primitive)return object("kind","primitive","type",primitive.type().name(),"value",primitive.toString());
        if(value instanceof StringReference string){String text=string.value();int max=Arguments.integer(args,"maxStringLength",256,1,1000),end=Math.min(text.length(),max);if(end<text.length()&&end>0&&Character.isHighSurrogate(text.charAt(end-1)))end--;return object("kind","string","type","java.lang.String","value",text.substring(0,end),"length",text.length(),"truncated",end<text.length());}
        if(value instanceof ObjectReference object)return object("kind",value instanceof ArrayReference?"array":"object","type",object.referenceType().name(),"objectId",Long.toString(object.uniqueID()),"length",value instanceof ArrayReference array?array.length():null);
        return object("kind","unavailable");
    }
    private static int offset(JsonObject args){return Arguments.integer(args,"offset",0,0,1000000);}
    private static int limit(JsonObject args){return Arguments.integer(args,"limit",100,1,200);}
    private static JsonObject page(String field,JsonArray values,int total,JsonObject args){int offset=offset(args);boolean more=offset+values.size()<total;return object(field,values,"total",total,"offset",offset,"limit",limit(args),"hasMore",more,"nextOffset",more?offset+values.size():null);}

    private JsonObject breakpointRequest(String name,JsonObject args){
        var manager=XDebuggerManager.getInstance(project).getBreakpointManager();
        List<XLineBreakpoint<?>> existing=new ArrayList<>();
        for(var breakpoint:manager.getAllBreakpoints())if(breakpoint instanceof XLineBreakpoint<?> line&&line.getType() instanceof JavaLineBreakpointType&&inProject(line))existing.add(line);
        breakpoints.keySet().retainAll(existing);existing.forEach(b->breakpoints.computeIfAbsent(b,k->UUID.randomUUID().toString()));
        if(name.equals("list_breakpoints")){JsonArray items=new JsonArray();existing.stream().sorted(Comparator.comparing((XLineBreakpoint<?> b)->b.getFileUrl()).thenComparingInt(XLineBreakpoint::getLine)).forEach(b->items.add(breakpointJson(b)));return object("breakpoints",items,"scope","project-wide Java line breakpoints, shared with IDEA");}
        if(name.equals("remove_breakpoint")){
            String id=Arguments.required(args,"breakpointId");XLineBreakpoint<?> found=existing.stream().filter(b->id.equals(breakpoints.get(b))).findFirst().orElseThrow(()->new IllegalArgumentException("BREAKPOINT_NOT_FOUND: list breakpoints in this project first"));
            WriteAction.run(()->manager.removeBreakpoint(found));breakpoints.remove(found);return object("breakpointId",id,"removed",true);
        }
        if(DumbService.isDumb(project))throw new IllegalArgumentException("INDEXING: retry after indexing");
        if(PsiDocumentManager.getInstance(project).hasUncommitedDocuments())throw new IllegalArgumentException("DOCUMENTS_UNCOMMITTED: retry shortly");
        var file=JavaCodeTools.file(project,Arguments.required(args,"file"));var document=PsiDocumentManager.getInstance(project).getDocument(file);
        int line=Arguments.integer(args,"line",0,1,Integer.MAX_VALUE)-1;
        if(document==null||line>=document.getLineCount())throw new IllegalArgumentException("line is outside file");
        JavaLineBreakpointType type=XBreakpointType.EXTENSION_POINT_NAME.getExtensionList().stream().filter(t->t.getClass()==JavaLineBreakpointType.class).map(t->(JavaLineBreakpointType)t).findFirst().orElseThrow(()->new IllegalArgumentException("JAVA_BREAKPOINT_TYPE_UNAVAILABLE"));
        if(!type.canPutAt(file.getVirtualFile(),line,project))throw new IllegalArgumentException("NOT_BREAKPOINT_LINE: select an executable Java source line");
        var matches=manager.findBreakpointsAtLine(type,file.getVirtualFile(),line);
        if(matches.size()>1)throw new IllegalArgumentException("AMBIGUOUS_BREAKPOINT: multiple inline breakpoints exist at this line; manage them in IDEA");
        boolean created=matches.isEmpty(),enabled=Arguments.bool(args,"enabled",true);
        XLineBreakpoint<?> b=WriteAction.compute(()->{var bp=created?manager.addLineBreakpoint(type,file.getVirtualFile().getUrl(),line,type.createProperties()):matches.iterator().next();bp.setEnabled(enabled);return bp;});
        breakpoints.computeIfAbsent(b,k->UUID.randomUUID().toString());JsonObject result=breakpointJson(b);result.addProperty("created",created);return result;
    }
    private boolean inProject(XLineBreakpoint<?> b){var file=VirtualFileManager.getInstance().findFileByUrl(b.getFileUrl());return file!=null&&ProjectFileIndex.getInstance(project).isInContent(file);}
    private JsonObject breakpointJson(XLineBreakpoint<?> b){return object("breakpointId",breakpoints.get(b),"fileUrl",b.getFileUrl(),"line",b.getLine()+1,"enabled",b.isEnabled(),"suspendPolicy",b.getSuspendPolicy().name(),"hasCondition",b.getConditionExpression()!=null,"hasLogExpression",b.getLogExpressionObject()!=null,"temporary",b.isTemporary());}
    @Override public void dispose(){disposed=true;sessions.values().forEach(Entry::detach);sessions.clear();breakpoints.clear();}
}
