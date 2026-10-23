package dev.ideamcp;

import com.google.gson.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.*;
import com.intellij.testFramework.*;
import com.intellij.testFramework.builders.JavaModuleFixtureBuilder;
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase;
import com.intellij.util.concurrency.AppExecutorUtil;
import dev.ideamcp.protocol.RpcHandler;
import static dev.ideamcp.protocol.Json.object;

public final class DiagnosticsToolsTest extends JavaCodeInsightFixtureTestCase {
    @Override protected void tuneFixture(JavaModuleFixtureBuilder builder) throws Exception { super.tuneFixture(builder);builder.addJdk(System.getProperty("java.home"));builder.setLanguageLevel(LanguageLevel.JDK_21); }
    public void testAnalyzesClosedFileErrorsAndSeverityWithPositions() throws Exception {
        PsiFile file=source("class Broken { void run() { /* 😀 */ missing(); alsoMissing(); } }");
        JsonObject result=query(file,object("severity","error"));
        assertEquals(result.toString(),2,result.get("total").getAsInt());
        for(JsonElement diagnostic:result.getAsJsonArray("diagnostics")) {
            var item=diagnostic.getAsJsonObject();assertEquals("error",item.get("severity").getAsString());assertFalse(item.get("message").getAsString().isBlank());
            int column=item.getAsJsonObject("location").get("column").getAsInt();assertTrue(column>file.getText().indexOf("😀"));
        }
        JsonObject one=query(file,object("severity","error","limit",1));assertTrue(one.get("hasMore").getAsBoolean());assertEquals(1,one.get("nextOffset").getAsInt());
        JsonObject two=query(file,object("severity","error","offset",1,"limit",1));assertFalse(two.get("hasMore").getAsBoolean());
        assertEquals(result.getAsJsonArray("diagnostics").get(1),two.getAsJsonArray("diagnostics").get(0));
        assertEquals(0,query(file,object("severity","warning")).get("total").getAsInt());
        assertEquals(0,query(file,object("offset",1000000)).getAsJsonArray("diagnostics").size());
    }
    public void testCommittedUnsavedFixRemovesStaleErrors() throws Exception {
        PsiFile file=source("class Broken { void run() { missing(); } }");
        JsonObject before=query(file,object("severity","error"));assertEquals(1,before.get("total").getAsInt());
        var doc=PsiDocumentManager.getInstance(getProject()).getDocument(file);
        WriteCommandAction.runWriteCommandAction(getProject(),()->doc.setText("class Broken { void run() {} }"));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        JsonObject after=query(file,object("severity","error"));assertEquals(after.toString(),0,after.get("total").getAsInt());assertFalse(before.get("documentStamp").equals(after.get("documentStamp")));
    }
    public void testEnabledInspectionProducesWarnings() throws Exception {
        myFixture.enableInspections(new com.intellij.codeInspection.deprecation.DeprecationInspection());
        myFixture.addFileToProject("Old.java","class Old { @Deprecated static void old(){} }");
        PsiFile file=source("class Broken { void run(){Old.old();} }");
        JsonObject result=query(file,object("severity","warning"));
        assertTrue(result.toString(),result.get("total").getAsInt()>0);
        for(JsonElement item:result.getAsJsonArray("diagnostics"))assertEquals("warning",item.getAsJsonObject().get("severity").getAsString());
        assertEquals(0,query(file,object("severity","error")).get("total").getAsInt());
    }
    public void testRejectsOutsideProjectAndNonJavaFiles() throws Exception {
        PsiFile text=myFixture.addFileToProject("notes.txt","anything");
        JsonObject bad=rpc(text.getVirtualFile().getPath(),object());assertTrue(bad.get("isError").getAsBoolean());assertTrue(bad.toString().contains("NOT_JAVA"));
        bad=rpc(System.getProperty("java.home")+"/release",object());assertTrue(bad.get("isError").getAsBoolean());
    }
    private PsiFile source(String code) {PsiFile f=myFixture.addFileToProject("Broken.java",code);PsiDocumentManager.getInstance(getProject()).commitAllDocuments();IndexingTestUtil.waitUntilIndexesAreReady(getProject());return f;}
    private JsonObject query(PsiFile file,JsonObject args)throws Exception {JsonObject r=rpc(file.getVirtualFile().getPath(),args);assertFalse(r.toString(),r.get("isError").getAsBoolean());return r.getAsJsonObject("structuredContent");}
    private JsonObject rpc(String file,JsonObject args)throws Exception {
        args.addProperty("project",getProject().getBasePath());args.addProperty("file",file);
        return PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(()->new RpcHandler(new IdeaToolBackend()).handle(object("jsonrpc","2.0","id",1,"method","tools/call","params",object("name","get_diagnostics","arguments",args)))),30000).getAsJsonObject("result");
    }
}
