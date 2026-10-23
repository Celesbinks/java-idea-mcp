package dev.ideamcp;

import com.google.gson.*;
import com.intellij.execution.*;
import com.intellij.execution.application.ApplicationConfigurationType;
import com.intellij.execution.process.*;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase;
import com.intellij.openapi.util.Disposer;
import java.io.OutputStream;
import static dev.ideamcp.protocol.Json.object;

public final class RunLogServiceTest extends JavaCodeInsightFixtureTestCase {
    public void testCapturesEventsAndExcludesSystemCommandLine() throws Exception {
        RunLogService logs=getProject().getService(RunLogService.class);
        var settings=RunManager.getInstance(getProject()).createConfiguration("log test",ApplicationConfigurationType.getInstance().getConfigurationFactories()[0]);
        var env=ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(),settings).build();env.assignNewExecutionId();
        FakeHandler handler=new FakeHandler();handler.startNotify();
        getProject().getMessageBus().syncPublisher(ExecutionManager.EXECUTION_TOPIC).processStarting("Run",env,handler);
        getProject().getMessageBus().syncPublisher(ExecutionManager.EXECUTION_TOPIC).processStarted("Run",env,handler);
        handler.notifyTextAvailable("java -Dsecret=fixture\n",ProcessOutputTypes.SYSTEM);
        handler.notifyTextAvailable("started\n",ProcessOutputTypes.STDOUT);handler.notifyTextAvailable("failure\n",ProcessOutputTypes.STDERR);
        JsonObject result=logs.read(object("configurationId",settings.getUniqueID(),"cursor","0"));
        assertEquals("started\nfailure\n",RunLogBufferTest.text(result));assertFalse(result.get("historyBeforeCaptureAvailable").getAsBoolean());assertEquals(1,logs.retained().size());
        handler.finish();assertTrue(logs.snapshot(object("configurationId",settings.getUniqueID())).get("terminated").getAsBoolean());
    }
    public void testRestartIsolationAndExactSessionSelection() {
        RunLogService logs=getProject().getService(RunLogService.class);FakeHandler old=new FakeHandler(),fresh=new FakeHandler();old.startNotify();fresh.startNotify();
        String oldId=logs.attach("config","same name",old,1);old.notifyTextAvailable("old",ProcessOutputTypes.STDOUT);old.finish();
        String freshId=logs.attach("config","same name",fresh,2);fresh.notifyTextAvailable("new",ProcessOutputTypes.STDOUT);
        assertFalse(oldId.equals(freshId));assertEquals(freshId,logs.attach("config","same name",fresh,2));
        assertThrows(IllegalArgumentException.class,()->logs.snapshot(object("configurationId","config")));
        assertEquals("old",RunLogBufferTest.text(logs.snapshot(object("configurationId","config","sessionId",oldId,"cursor","0"))));
        assertEquals("new",RunLogBufferTest.text(logs.snapshot(object("configurationId","config","sessionId",freshId,"cursor","0"))));
        assertThrows(IllegalArgumentException.class,()->logs.snapshot(object("configurationId","other","sessionId",freshId)));
        assertThrows(IllegalArgumentException.class,()->logs.snapshot(object("configurationId","config","sessionId","missing")));
        fresh.finish();
    }
    public void testRetentionLimitAndDisposeDetachWithoutStoppingProcesses() {
        RunLogService logs=new RunLogService(getProject());Disposer.register(getTestRootDisposable(),logs);
        FakeHandler live=new FakeHandler();live.startNotify();String liveId=logs.attach("live","live",live,1);
        for(int i=0;i<40;i++){FakeHandler ended=new FakeHandler();ended.startNotify();logs.attach("ended","ended",ended,i+2);ended.finish();}
        assertEquals(32,logs.retained().size());assertNotNull(logs.snapshot(object("configurationId","live","sessionId",liveId)));
        Disposer.dispose(logs);assertFalse(live.isProcessTerminated());assertEquals(0,live.destroyRequests);
        live.notifyTextAvailable("after detach",ProcessOutputTypes.STDOUT);assertThrows(IllegalArgumentException.class,()->logs.snapshot(object("configurationId","live")));live.finish();
    }
    private static final class FakeHandler extends ProcessHandler {
        int destroyRequests;
        @Override protected void destroyProcessImpl(){destroyRequests++;notifyProcessTerminated(0);}
        @Override protected void detachProcessImpl(){notifyProcessDetached();}
        @Override public boolean detachIsDefault(){return false;}
        @Override public OutputStream getProcessInput(){return null;}
        void finish(){notifyProcessTerminated(0);}
    }
}
