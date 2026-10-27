package dev.ideamcp;

import com.google.gson.*;
import com.intellij.openapi.application.*;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.newvfs.*;
import com.intellij.testFramework.*;
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase;
import com.intellij.util.concurrency.AppExecutorUtil;
import dev.ideamcp.protocol.RpcHandler;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static dev.ideamcp.protocol.Json.object;

/** Real providers, disposable projects, and fault injection confined to this test JVM. */
public final class EditorPersistenceTest extends JavaCodeInsightFixtureTestCase {
    private com.intellij.mcpserver.McpSessionInvocationMode oldMode;
    private Map<String,com.intellij.mcpserver.settings.McpToolDisallowListSettings.ToolState> oldStates;
    private Path root;
    private static final String RAW="class Probe {int number=1;int read(){return number;}}\n";
    @Override protected void setUp() throws Exception {
        super.setUp(); root=Path.of(getProject().getBasePath());
        var vf=WriteAction.compute(()->VfsUtil.createDirectories(root.toString()));
        if(!com.intellij.openapi.roots.ProjectFileIndex.getInstance(getProject()).isInContent(vf))PsiTestUtil.addContentRoot(getModule(),vf);
        PsiTestUtil.addSourceRoot(getModule(),vf);
        var settings=com.intellij.mcpserver.settings.McpToolDisallowListSettings.getInstance();oldStates=settings.getToolStates();settings.setToolStates(Map.of());
        var filter=com.intellij.mcpserver.settings.McpToolFilterSettings.getInstance();oldMode=filter.getInvocationMode();filter.setInvocationMode(com.intellij.mcpserver.McpSessionInvocationMode.DIRECT);
    }
    @Override protected void tearDown() throws Exception {
        try {
            com.intellij.mcpserver.settings.McpToolDisallowListSettings.getInstance().setToolStates(oldStates);
            com.intellij.mcpserver.settings.McpToolFilterSettings.getInstance().setInvocationMode(oldMode);
        } finally {super.tearDown();}
    }
    private PlatformOperationService operations(){return ApplicationManager.getApplication().getService(PlatformOperationService.class);}
    private String submit(String name,JsonObject args) throws Exception {
        return submit(name,args,null);
    }
    private String submit(String name,JsonObject args,AtomicReference<String> capture) throws Exception {
        args.addProperty("projectPath",getProject().getBasePath());
        return background(()->{
            var envelope=new RpcHandler(new IdeaToolBackend()).handle(object("jsonrpc","2.0","id",1,"method","tools/call","params",object("name",name,"arguments",args)));
            assertFalse(envelope.toString(),envelope.has("error"));var response=envelope.getAsJsonObject("result");
            assertFalse(response.toString(),response.get("isError").getAsBoolean());
            assertTrue("Editor must return a tracked operation: "+response,response.getAsJsonObject("structuredContent").has("operationId"));
            String id=response.getAsJsonObject("structuredContent").get("operationId").getAsString();
            if(capture!=null)capture.set(id);return id;
        });
    }
    private JsonObject await(String id) {
        PlatformTestUtil.waitWithEventsDispatching("Editor operation did not finish",()->!operations().query(object("operationId",id),false).get("inProgress").getAsBoolean(),45);
        return operations().query(object("operationId",id),false);
    }
    private JsonObject persisted(String id) {
        var operation=await(id);assertEquals(operation.toString(),"completed",operation.get("phase").getAsString());
        assertFalse(operation.toString(),operation.getAsJsonObject("result").get("isError").getAsBoolean());
        assertEquals(operation.toString(),"persisted",operation.getAsJsonObject("editorWrite").get("status").getAsString());return operation;
    }
    private VirtualFile create() throws Exception {
        persisted(submit("create_new_file",object("pathInProject","Probe.java","text",RAW,"overwrite",false)));
        var file=LocalFileSystem.getInstance().findFileByNioFile(root.resolve("Probe.java"));assertNotNull(file);return file;
    }
    public void testEditorToolsPublishAsyncMutatingContract() throws Exception {
        background(()->{
            for(String name:EditorWriteBoundary.TOOLS){
                var descriptor=new IdeaToolBackend().tool(name);assertNotNull(name,descriptor);
                assertTrue(descriptor.toString(),descriptor.get("description").getAsString().contains("operationId"));
                assertFalse(descriptor.has("outputSchema"));
                assertFalse(descriptor.getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean());
            }return null;
        });
    }
    public void testCreateCompletionFlushesDelayedPhysicalWrite() throws Exception {
        try(var gate=new DiskGate()){
            persisted(submit("create_new_file",object("pathInProject","Probe.java","text",RAW,"overwrite",false)));
            assertTrue("Injected background disk delay was not exercised",gate.scheduled.get()>0);
            assertFalse("Safety release masked an unfinished save",gate.expired.get());
            assertEquals(RAW,normalized(Files.readString(root.resolve("Probe.java"))));
        }
    }
    public void testFormatCompletionFlushesDelayAndLeavesOtherDirtyDocumentAlone() throws Exception {
        var file=create();
        var unrelated=myFixture.addFileToProject("Unrelated.java","class Unrelated {}\n");
        Document other=FileDocumentManager.getInstance().getDocument(unrelated.getVirtualFile());assertNotNull(other);
        FileDocumentManager.getInstance().saveDocument(other);
        background(()->{ManagingFS.getInstance().flushPendingUpdates(unrelated.getVirtualFile());return null;});
        WriteAction.run(()->other.setText("class Unrelated { int unsaved; }\n"));
        try(var gate=new DiskGate()){
            persisted(submit("reformat_file",object("files",new String[]{"Probe.java"})));
            assertTrue(gate.scheduled.get()>0);assertFalse(gate.expired.get());
            Document doc=FileDocumentManager.getInstance().getDocument(file);assertNotNull(doc);
            assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(doc));
            assertEquals(doc.getText(),normalized(Files.readString(root.resolve("Probe.java"))));
            assertTrue("Unrelated user edits were saved",FileDocumentManager.getInstance().isDocumentUnsaved(other));
            assertFalse(Files.readString(Path.of(unrelated.getVirtualFile().getPath())).contains("unsaved"));
        }
    }
    public void testFormatRemainsTrackedPastOldTwentySecondTimeout() throws Exception { delayedFormatting(false,22); }
    public void testCancellationAfterEditingWaitsForSaveAndDoesNotInterruptProvider() throws Exception { delayedFormatting(true,1); }
    private void delayedFormatting(boolean cancel,int seconds) throws Exception {
        var file=create();Document document=FileDocumentManager.getInstance().getDocument(file);assertNotNull(document);
        AtomicBoolean held=new AtomicBoolean(),wasInProgress=new AtomicBoolean();
        AtomicReference<String> id=new AtomicReference<>(),observedPhase=new AtomicReference<>();
        CountDownLatch release=new CountDownLatch(1);
        var timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"editor-persistence-test-release");t.setDaemon(true);return t;});
        DocumentListener listener=new DocumentListener(){
            @Override public void documentChanged(DocumentEvent event){
                if(!held.compareAndSet(false,true))return;
                timer.schedule(()->{
                    String operation=id.get();
                    if(operation!=null){var status=operations().query(object("operationId",operation),cancel);wasInProgress.set(status.get("inProgress").getAsBoolean());observedPhase.set(status.get("phase").getAsString());}
                },cancel?100:20_500,TimeUnit.MILLISECONDS);
                timer.schedule(release::countDown,seconds,TimeUnit.SECONDS);
                try{assertTrue("Release timer failed",release.await(seconds+5,TimeUnit.SECONDS));}
                catch(InterruptedException failure){throw new AssertionError(failure);}
            }
        };
        document.addDocumentListener(listener);
        try{
            submit("reformat_file",object("files",new String[]{"Probe.java"}),id);
            var operation=await(id.get());assertTrue("Formatter was not delayed",held.get());assertTrue(wasInProgress.get());
            assertEquals(operation.toString(),cancel?"cancelled":"completed",operation.get("phase").getAsString());
            assertEquals(cancel?"cancel_requested":"running",observedPhase.get());
            assertEquals(operation.toString(),"persisted",operation.getAsJsonObject("editorWrite").get("status").getAsString());
            assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(document));
            assertEquals(document.getText(),normalized(Files.readString(root.resolve("Probe.java"))));
        }finally{
            release.countDown();document.removeDocumentListener(listener);timer.shutdownNow();timer.awaitTermination(3,TimeUnit.SECONDS);
        }
    }
    public void testRouterEditsAlsoWaitForPhysicalPersistence() throws Exception {
        com.intellij.mcpserver.settings.McpToolFilterSettings.getInstance().setInvocationMode(com.intellij.mcpserver.McpSessionInvocationMode.VIA_ROUTER);
        try(var gate=new DiskGate()){
            var operation=persisted(submit("execute_tool",object("command","create_new_file --pathInProject router.txt --text fixture")));
            assertTrue(operation.toString(),operation.getAsJsonObject("editorWrite").get("files").getAsInt()>0);
            assertTrue(gate.scheduled.get()>0);assertFalse(gate.expired.get());
            assertEquals("fixture",Files.readString(root.resolve("router.txt")));
        }
    }
    public void testUtf16BomAndCrLfPersistWithoutReencoding() throws Exception {
        var file=WriteAction.compute(()->getProject().getBaseDir().createChildData(this,"Encoded.java"));
        WriteAction.run(()->{
            file.setCharset(StandardCharsets.UTF_16LE);file.setBOM(new byte[]{(byte)0xff,(byte)0xfe});
            VfsUtil.saveText(file,"class Encoded {int value=1;}\r\n");
        });
        background(()->{ManagingFS.getInstance().flushPendingUpdates(file);return null;});
        persisted(submit("reformat_file",object("files",new String[]{"Encoded.java"})));
        byte[] disk=Files.readAllBytes(root.resolve("Encoded.java"));assertEquals((byte)0xff,disk[0]);assertEquals((byte)0xfe,disk[1]);
        assertEquals("UTF-16LE",file.getCharset().name());
        assertTrue("CRLF line endings changed",new String(disk,StandardCharsets.UTF_16LE).contains("\r\n"));
    }
    public void testPatchAndRenameFinishWithPersistedRelatedFiles() throws Exception {
        var file=create();
        persisted(submit("apply_patch",object("input","*** Begin Patch\n*** Update File: Probe.java\n@@\n-class Probe {int number=1;int read(){return number;}}\n+class Probe {int number=2;int read(){return number;}}\n*** End Patch")));
        persisted(submit("rename_refactoring",object("pathInProject","Probe.java","symbolName","number","newName","amount")));
        String disk=Files.readString(root.resolve("Probe.java"));assertTrue(disk.contains("amount"));assertFalse(disk.contains("number"));
        assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(FileDocumentManager.getInstance().getDocument(file)));
    }
    public void testExistingUnsavedTargetIsRejectedWithoutSavingIt() throws Exception {
        var file=create();Document document=FileDocumentManager.getInstance().getDocument(file);assertNotNull(document);
        WriteAction.run(()->document.setText("class Probe { int keepUnsaved; }\n"));
        var operation=await(submit("reformat_file",object("files",new String[]{"Probe.java"})));
        assertEquals("failed",operation.get("phase").getAsString());
        assertTrue(operation.toString(),operation.get("error").getAsString().contains("EDITOR_UNSAVED_DOCUMENT"));
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document));assertEquals(RAW,normalized(Files.readString(root.resolve("Probe.java"))));
    }
    public void testConcurrentEditorChangeIsNotAutomaticallySaved() throws Exception {
        var file=create();Document document=FileDocumentManager.getInstance().getDocument(file);assertNotNull(document);
        try(var boundary=new EditorWriteBoundary(getProject(),"reformat_file",object("files",new String[]{"Probe.java"}))){
            try(var token=com.intellij.concurrency.ThreadContext.installThreadContext(com.intellij.concurrency.ThreadContext.currentThreadContext().plus(boundary.context()),true)){
                WriteAction.run(()->document.setText("class Probe { int toolChange; }\n"));
            }
            WriteAction.run(()->document.setText("class Probe { int concurrentChange; }\n"));
            var state=background(()->boundary.finish(true));assertEquals(state.toString(),"incomplete",state.get("status").getAsString());
            assertTrue(state.toString().contains("EDITOR_CHANGED_DURING_WRITE"));
            assertTrue(document.getText().contains("concurrentChange"));assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document));
            assertEquals(RAW,normalized(Files.readString(root.resolve("Probe.java"))));
        }
    }
    public void testAnotherEditorOperationIsRejectedUntilTheOwnerFinishes() throws Exception {
        create();
        try(var boundary=new EditorWriteBoundary(getProject(),"reformat_file",object("files",new String[]{"Probe.java"}))){
            var operation=await(submit("create_new_file",object("pathInProject","busy.txt","text","fixture","overwrite",false)));
            assertEquals("failed",operation.get("phase").getAsString());assertTrue(operation.toString().contains("EDITOR_WRITE_BUSY"));
            assertFalse(Files.exists(root.resolve("busy.txt")));
        }
        persisted(submit("create_new_file",object("pathInProject","busy.txt","text","fixture","overwrite",false)));
    }
    public void testExternalDiskUpdateIsPreservedOrReportedAsConflict() throws Exception {
        create();Path path=root.resolve("Probe.java");
        String outside="class Probe { int externalDiskChange = 7; }\n";
        Files.writeString(path,outside);
        var operation=await(submit("reformat_file",object("files",new String[]{"Probe.java"})));
        assertTrue("External disk contents were overwritten",Files.readString(path).contains("externalDiskChange"));
        if(operation.get("phase").getAsString().equals("completed"))
            assertEquals("persisted",operation.getAsJsonObject("editorWrite").get("status").getAsString());
        else {
            assertEquals("failed",operation.get("phase").getAsString());
            assertTrue(operation.toString(),operation.toString().contains("EDITOR_DISK_CONFLICT") || operation.toString().contains("EDITOR_WRITE_INCOMPLETE"));
        }
    }
    public void testPatchMoveAndDeleteLeavePhysicalPathsConsistent() throws Exception {
        create();
        persisted(submit("apply_patch",object("input","*** Begin Patch\n*** Update File: Probe.java\n*** Move to: Moved.java\n@@\n-class Probe {int number=1;int read(){return number;}}\n+class Moved {int number=1;int read(){return number;}}\n*** End Patch")));
        assertFalse(Files.exists(root.resolve("Probe.java")));assertTrue(Files.readString(root.resolve("Moved.java")).contains("class Moved"));
        persisted(submit("apply_patch",object("input","*** Begin Patch\n*** Delete File: Moved.java\n*** End Patch")));
        assertFalse(Files.exists(root.resolve("Moved.java")));
    }
    public void testVetoedSaveCannotReportEditorCompletion() throws Exception {
        var mask=com.intellij.openapi.util.Disposer.newDisposable();
        com.intellij.openapi.util.Disposer.register(getTestRootDisposable(),mask);
        com.intellij.openapi.fileEditor.FileDocumentSynchronizationVetoer veto=new com.intellij.openapi.fileEditor.FileDocumentSynchronizationVetoer(){
            @Override public boolean maySaveDocument(Document document,boolean explicit){
                var file=FileDocumentManager.getInstance().getFile(document);return file==null||!file.getName().equals("Blocked.java");
            }
        };
        ExtensionTestUtil.maskExtensions(com.intellij.openapi.fileEditor.FileDocumentSynchronizationVetoer.EP_NAME,List.of(veto),mask);
        try{
            var operation=await(submit("create_new_file",object("pathInProject","Blocked.java","text","class Blocked {}\n","overwrite",false)));
            assertEquals(operation.toString(),"failed",operation.get("phase").getAsString());
            assertEquals("incomplete",operation.getAsJsonObject("editorWrite").get("status").getAsString());
            assertTrue(operation.toString(),operation.getAsJsonObject("result").get("isError").getAsBoolean());
            assertTrue(operation.toString().contains("EDITOR_DOCUMENT_UNSAVED"));
        }finally{
            com.intellij.openapi.util.Disposer.dispose(mask);
            var file=LocalFileSystem.getInstance().findFileByNioFile(root.resolve("Blocked.java"));
            if(file!=null){var doc=FileDocumentManager.getInstance().getCachedDocument(file);if(doc!=null)FileDocumentManager.getInstance().saveDocument(doc);background(()->{ManagingFS.getInstance().flushPendingUpdates(file);return null;});}
        }
    }
    public void testProviderSaveAllDoesNotSaveUnrelatedDirtyDocuments() throws Exception {
        create();
        var other=myFixture.addFileToProject("OtherDirty.java","class OtherDirty {}\n");
        var manager=FileDocumentManager.getInstance();var document=manager.getDocument(other.getVirtualFile());assertNotNull(document);
        manager.saveDocument(document);background(()->{ManagingFS.getInstance().flushPendingUpdates(other.getVirtualFile());return null;});
        WriteAction.run(()->document.setText("class OtherDirty { int unsaved; }\n"));
        persisted(submit("apply_patch",object("input","*** Begin Patch\n*** Update File: Probe.java\n@@\n-class Probe {int number=1;int read(){return number;}}\n+class Probe {int number=3;int read(){return number;}}\n*** End Patch")));
        assertTrue("Provider's save-all persisted an unrelated buffer",manager.isDocumentUnsaved(document));
        assertFalse(Files.readString(Path.of(other.getVirtualFile().getPath())).contains("unsaved"));
        manager.saveDocument(document);background(()->{ManagingFS.getInstance().flushPendingUpdates(other.getVirtualFile());return null;});
        assertFalse("An explicit user save was incorrectly blocked",manager.isDocumentUnsaved(document));
    }
    private static String normalized(String text){return text.replace("\r\n","\n");}
    private <T>T background(Callable<T> task)throws Exception{
        var future=AppExecutorUtil.getAppExecutorService().submit(task);
        try{return PlatformTestUtil.waitForFuture(future,45000);}finally{if(!future.isDone())future.cancel(true);}
    }
    /** Holds scheduling only; explicit flush can drain pending tasks without waiting. */
    private static final class DiskGate implements AutoCloseable {
        final AtomicInteger scheduled=new AtomicInteger();final AtomicBoolean expired=new AtomicBoolean();
        final CountDownLatch release=new CountDownLatch(1);final Field field;final Object executor;final ExecutorService original;
        DiskGate()throws Exception{
            ManagingFS.getInstance().flushPendingUpdates();var fs=LocalFileSystem.getInstance();
            assertTrue("Async VFS must be exercised",fs instanceof AsyncableFileSystem);
            Field owner=fs.getClass().getDeclaredField("ioTasksExecutor");owner.setAccessible(true);executor=owner.get(fs);
            field=executor.getClass().getDeclaredField("pendingTasksExecutor");field.setAccessible(true);original=(ExecutorService)field.get(executor);
            field.set(executor,new AbstractExecutorService(){
                public void execute(Runnable runnable){scheduled.incrementAndGet();original.execute(()->{
                    try{if(!release.await(5,TimeUnit.SECONDS))expired.set(true);runnable.run();}catch(InterruptedException failure){Thread.currentThread().interrupt();}
                });}
                public void shutdown(){}public List<Runnable> shutdownNow(){return List.of();}public boolean isShutdown(){return false;}
                public boolean isTerminated(){return false;}public boolean awaitTermination(long value,TimeUnit unit){return false;}
            });
        }
        @Override public void close()throws Exception{field.set(executor,original);release.countDown();ManagingFS.getInstance().flushPendingUpdates();}
    }
}
