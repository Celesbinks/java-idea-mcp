package dev.ideamcp;

import com.google.gson.*;
import com.intellij.execution.*;
import com.intellij.execution.application.*;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.BaseProcessHandler;
import com.intellij.execution.impl.ExecutionManagerImpl;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.vfs.*;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.*;
import com.intellij.testFramework.*;
import com.intellij.testFramework.builders.JavaModuleFixtureBuilder;
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase;
import com.intellij.util.concurrency.AppExecutorUtil;
import dev.ideamcp.protocol.RpcHandler;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static dev.ideamcp.protocol.Json.object;

/** Only its own fixture source, IDE breakpoint metadata and disposable local JVM are touched. */
public final class DebugServiceTest extends JavaCodeInsightFixtureTestCase {
    private final Set<ProcessHandler> processes=new CopyOnWriteArraySet<>();
    private static final String SOURCE="""
            public class McpDebugProbe {
                static int effect = 0;
                static class Sensitive {
                    public String toString() { effect++; return "unexpected rendering"; }
                }
                static int twice(int value) {
                    int doubled = value * 2;
                    return doubled;
                }
                public static void main(String[] args) throws Exception {
                    int count = 7;
                    String text = "hello 😀";
                    Sensitive sensitive = new Sensitive();
                    int[] numbers = {1, 2, 3};
                    int result = twice(count);
                    count++;
                    System.out.println("EFFECT=" + effect);
                    Thread.sleep(120000L);
                }
            }
            """;
    @Override protected void tuneFixture(JavaModuleFixtureBuilder builder)throws Exception{super.tuneFixture(builder);builder.addJdk(System.getProperty("idea.mcp.test.jdk",System.getProperty("java.home")));builder.setLanguageLevel(LanguageLevel.JDK_21);}
    @Override protected void setUp()throws Exception{
        super.setUp();
        com.intellij.openapi.util.registry.Registry.get("profiler.widget.in.run.console").setValue(false,getTestRootDisposable());
        WriteAction.run(()->{var sdk=ModuleRootManager.getInstance(getModule()).getSdk();var modifier=sdk.getSdkModificator();modifier.setVersionString("java version \"21\"");modifier.commitChanges();});
        getProject().getService(RunLogService.class);
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(ExecutionManager.EXECUTION_TOPIC,new ExecutionListener(){@Override public void processStarting(String executor,ExecutionEnvironment env,ProcessHandler handler){processes.add(handler);}});
    }
    @Override protected void tearDown()throws Exception{
        try{for(ProcessHandler handler:processes)if(!handler.isProcessTerminated())ExecutionManagerImpl.stopProcess(handler);
            PlatformTestUtil.waitWithEventsDispatching("Probe JVM did not exit",()->processes.stream().allMatch(h->h.isProcessTerminated()&&(!(h instanceof BaseProcessHandler<?> n)||!n.getProcess().isAlive())),15);
        }finally{super.tearDown();}
    }
    public void testBreakpointLifecycleValidationAndWorkerDispatch(){
        PsiFile file=source();int line=line("int result = twice(count);");
        JsonObject added=call("set_breakpoint",object("file",file.getVirtualFile().getPath(),"line",line));assertTrue(added.get("created").getAsBoolean());String id=added.get("breakpointId").getAsString();
        JsonObject disabled=call("set_breakpoint",object("file",file.getVirtualFile().getPath(),"line",line,"enabled",false));assertEquals(id,disabled.get("breakpointId").getAsString());assertFalse(disabled.get("enabled").getAsBoolean());assertFalse(disabled.get("created").getAsBoolean());
        assertEquals(1,call("list_breakpoints",object()).getAsJsonArray("breakpoints").size());
        assertTrue(error("set_breakpoint",object("file",file.getVirtualFile().getPath(),"line",100000)).contains("outside"));
        PsiFile notes=myFixture.addFileToProject("notes.txt","not Java");assertTrue(error("set_breakpoint",object("file",notes.getVirtualFile().getPath(),"line",1)).contains("NOT_JAVA"));
        assertTrue(error("set_breakpoint",object("file",System.getProperty("java.home")+"/release","line",1)).length()>0);
        assertTrue(call("remove_breakpoint",object("breakpointId",id)).get("removed").getAsBoolean());assertEquals(0,call("list_breakpoints",object()).getAsJsonArray("breakpoints").size());
        assertTrue(error("remove_breakpoint",object("breakpointId",id)).contains("BREAKPOINT_NOT_FOUND"));
    }
    public void testRejectsUnknownSessionsAndTokensWithoutLaunchingAnything(){
        assertEquals(0,call("list_debug_sessions",object()).getAsJsonArray("sessions").size());
        for(String name:List.of("read_debug_threads","read_debug_stack","read_debug_variables","control_debugger")){
            JsonObject args=object("sessionId","missing","pauseId","stale","threadId","1");
            if(name.equals("read_debug_threads"))args.remove("threadId");
            if(name.equals("control_debugger")){args.remove("threadId");args.addProperty("action","resume");}
            assertTrue(error(name,args).contains("DEBUG_SESSION_NOT_FOUND"));
        }
        assertTrue(processes.isEmpty());
    }
    public void testRealJvmBreakpointsThreadsFramesVariablesAndStepControls()throws Exception{
        PsiFile file=source();var settings=configuration(file);
        String breakpoint=call("set_breakpoint",object("file",file.getVirtualFile().getPath(),"line",line("int result = twice(count);"))).get("breakpointId").getAsString();
        RunControlService run=getProject().getService(RunControlService.class);
        JsonObject submitted=run.call("restart_run_configuration",object("configurationId",settings.getUniqueID(),"mode","debug","startTimeoutSeconds",30));
        String operation=submitted.get("operationId").getAsString();
        PlatformTestUtil.waitWithEventsDispatching("Debug launch failed",()->!run.call("get_run_operation",object("operationId",operation)).get("inProgress").getAsBoolean(),45);
        JsonObject started=run.call("get_run_operation",object("operationId",operation));assertEquals(started.toString(),"started",started.get("phase").getAsString());assertTrue(started.get("debuggerAttached").getAsBoolean());
        JsonObject paused=awaitPause(null,null);String session=paused.get("sessionId").getAsString(),pause=paused.get("pauseId").getAsString();
        JsonObject snapshot=object("sessionId",session,"pauseId",pause);
        JsonObject threads=call("read_debug_threads",snapshot.deepCopy());assertTrue(threads.get("total").getAsInt()>0);
        String thread=threads.getAsJsonArray("threads").asList().stream().map(JsonElement::getAsJsonObject).filter(t->t.get("name").getAsString().equals("main")).findFirst().orElseThrow().get("threadId").getAsString();snapshot.addProperty("threadId",thread);
        JsonObject frames=call("read_debug_stack",snapshot.deepCopy());assertEquals("main",frames.getAsJsonArray("frames").get(0).getAsJsonObject().get("method").getAsString());
        JsonObject vars=call("read_debug_variables",snapshot.deepCopy());assertEquals("7",variable(vars,"count").get("value").getAsString());assertEquals("hello 😀",variable(vars,"text").get("value").getAsString());assertEquals("object",variable(vars,"sensitive").get("kind").getAsString());assertEquals(3,variable(vars,"numbers").get("length").getAsInt());
        JsonObject single=snapshot.deepCopy();single.addProperty("limit",1);assertEquals(1,call("read_debug_variables",single).getAsJsonArray("variables").size());
        JsonObject empty=snapshot.deepCopy();empty.addProperty("offset",1000000);assertEquals(0,call("read_debug_stack",empty).getAsJsonArray("frames").size());
        JsonObject bad=snapshot.deepCopy();bad.addProperty("frameIndex",1000000);assertTrue(error("read_debug_variables",bad).contains("FRAME_NOT_FOUND"));
        assertTrue(error("read_debug_threads",object("sessionId",session,"pauseId","bad")).contains("STALE_PAUSE"));
        // A second paused VM must stay on exactly its own pause while the first is stepped.
        var peer=savedConfiguration("MCP isolated peer");
        String peerOperation=run.call("restart_run_configuration",object("configurationId",peer.getUniqueID(),"mode","debug","startTimeoutSeconds",30)).get("operationId").getAsString();
        PlatformTestUtil.waitWithEventsDispatching("Peer JVM did not start",()->!run.call("get_run_operation",object("operationId",peerOperation)).get("inProgress").getAsBoolean(),45);
        JsonObject[] peerPause={null};
        PlatformTestUtil.waitWithEventsDispatching("Peer VM did not pause",()->{for(JsonElement item:call("list_debug_sessions",object()).getAsJsonArray("sessions")){JsonObject s=item.getAsJsonObject();if(s.get("configurationId").getAsString().equals(peer.getUniqueID())&&s.get("state").getAsString().equals("suspended")){peerPause[0]=s;return true;}}return false;},20);
        control(session,pause,"step_into");JsonObject inside=awaitPause(session,pause);String next=inside.get("pauseId").getAsString();
        assertTrue(error("read_debug_stack",snapshot.deepCopy()).contains("STALE_PAUSE"));snapshot.addProperty("pauseId",next);
        assertEquals("twice",call("read_debug_stack",snapshot.deepCopy()).getAsJsonArray("frames").get(0).getAsJsonObject().get("method").getAsString());
        assertEquals("7",variable(call("read_debug_variables",snapshot.deepCopy()),"value").get("value").getAsString());
        control(session,next,"step_over");inside=awaitPause(session,next);next=inside.get("pauseId").getAsString();snapshot.addProperty("pauseId",next);
        assertEquals("14",variable(call("read_debug_variables",snapshot.deepCopy()),"doubled").get("value").getAsString());
        control(session,next,"step_out");inside=awaitPause(session,next);next=inside.get("pauseId").getAsString();snapshot.addProperty("pauseId",next);
        assertEquals("main",call("read_debug_stack",snapshot.deepCopy()).getAsJsonArray("frames").get(0).getAsJsonObject().get("method").getAsString());
        JsonObject peerStillPaused=awaitPause(peerPause[0].get("sessionId").getAsString(),null);assertEquals(peerPause[0].get("pauseId"),peerStillPaused.get("pauseId"));
        call("remove_breakpoint",object("breakpointId",breakpoint));control(session,next,"resume");
        RunLogService logs=getProject().getService(RunLogService.class);
        PlatformTestUtil.waitWithEventsDispatching("Probe output missing",()->RunLogBufferTest.text(logs.snapshot(object("configurationId",settings.getUniqueID(),"cursor","0"))).contains("EFFECT="),10);
        call("list_debug_sessions",object());call("control_debugger",object("sessionId",session,"action","pause"));inside=awaitPause(session,next);control(session,inside.get("pauseId").getAsString(),"resume");
    }
    private JsonObject awaitPause(String session,String old){
        JsonObject[] found={null};PlatformTestUtil.waitWithEventsDispatching("Debugger did not reach a new pause",()->{for(JsonElement item:call("list_debug_sessions",object()).getAsJsonArray("sessions")){JsonObject s=item.getAsJsonObject();if((session==null||session.equals(s.get("sessionId").getAsString()))&&s.get("state").getAsString().equals("suspended")&&!s.get("pauseId").isJsonNull()&&(old==null||!old.equals(s.get("pauseId").getAsString()))){found[0]=s;return true;}}return false;},20);return found[0];
    }
    private void control(String session,String pause,String action){assertTrue(call("control_debugger",object("sessionId",session,"pauseId",pause,"action",action)).get("accepted").getAsBoolean());}
    private static JsonObject variable(JsonObject result,String name){return result.getAsJsonArray("variables").asList().stream().map(JsonElement::getAsJsonObject).filter(v->v.get("name").getAsString().equals(name)).findFirst().orElseThrow().getAsJsonObject("value");}
    private PsiFile source(){PsiFile file=myFixture.addFileToProject("McpDebugProbe.java",SOURCE);PsiDocumentManager.getInstance(getProject()).commitAllDocuments();IndexingTestUtil.waitUntilIndexesAreReady(getProject());return file;}
    private static int line(String text){String before=SOURCE.substring(0,SOURCE.indexOf(text));return (int)before.chars().filter(c->c=='\n').count()+1;}
    private com.intellij.execution.RunnerAndConfigurationSettings configuration(PsiFile file)throws Exception{
        Path out=Files.createDirectories(Path.of(getProject().getBasePath(),"debug-test-classes"));String sdk=System.getProperty("idea.mcp.test.jdk",System.getProperty("java.home"));
        Process compiler=new ProcessBuilder(Path.of(sdk,"bin",System.getProperty("os.name").startsWith("Windows")?"javac.exe":"javac").toString(),"-g","-encoding","UTF-8","-d",out.toString(),file.getVirtualFile().getPath()).redirectErrorStream(true).start();
        try{assertTrue(compiler.waitFor(30,TimeUnit.SECONDS));assertEquals(new String(compiler.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8),0,compiler.exitValue());}finally{compiler.destroyForcibly();}
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(out);ModuleRootModificationUtil.updateModel(getModule(),m->{var output=m.getModuleExtension(CompilerModuleExtension.class);output.inheritCompilerOutputPath(false);output.setCompilerOutputPath(VfsUtilCore.pathToUrl(out.toString()));});IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        return savedConfiguration("MCP debug fixture");
    }
    private com.intellij.execution.RunnerAndConfigurationSettings savedConfiguration(String name){var manager=RunManager.getInstance(getProject());var settings=manager.createConfiguration(name,ApplicationConfigurationType.getInstance().getConfigurationFactories()[0]);var config=(ApplicationConfiguration)settings.getConfiguration();config.setModule(getModule());config.setMainClassName("McpDebugProbe");config.setBeforeRunTasks(List.of());config.setWorkingDirectory(getProject().getBasePath());settings.setEditBeforeRun(false);manager.addConfiguration(settings);return settings;}
    private JsonObject call(String name,JsonObject args){JsonObject r=rpc(name,args);assertFalse(r.toString(),r.get("isError").getAsBoolean());return r.getAsJsonObject("structuredContent");}
    private String error(String name,JsonObject args){JsonObject r=rpc(name,args);assertTrue(r.toString(),r.get("isError").getAsBoolean());return r.getAsJsonObject("structuredContent").get("error").getAsString();}
    private JsonObject rpc(String name,JsonObject args){args.addProperty("project",getProject().getBasePath());try{return PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(()->new RpcHandler(new IdeaToolBackend()).handle(object("jsonrpc","2.0","id",1,"method","tools/call","params",object("name",name,"arguments",args)))),25000).getAsJsonObject("result");}catch(Exception e){throw new AssertionError(e);}}
}
