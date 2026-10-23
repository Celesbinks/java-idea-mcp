package dev.ideamcp;

import com.google.gson.*;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.testFramework.*;
import com.intellij.testFramework.builders.JavaModuleFixtureBuilder;
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase;
import com.intellij.util.concurrency.AppExecutorUtil;
import dev.ideamcp.protocol.RpcHandler;
import java.util.*;
import static dev.ideamcp.protocol.Json.object;

public final class JavaCallToolsTest extends JavaCodeInsightFixtureTestCase {
    @Override protected void tuneFixture(JavaModuleFixtureBuilder builder) throws Exception {
        super.tuneFixture(builder); builder.addJdk(System.getProperty("java.home")); builder.setLanguageLevel(LanguageLevel.JDK_21);
    }
    public void testExactOverloadAndIndividualIncomingCallSites() throws Exception {
        String code = "class Calls { void pick(int n) {} void pick(long n) {} void run() { pick(1); pick(2); pick(3L); } }";
        PsiFile file = source(code);
        JsonObject callers = query("find_callers", file, code.indexOf("pick(int"), object());
        assertEquals(2, callers.get("total").getAsInt());
        for (JsonElement item : callers.getAsJsonArray("calls")) {
            assertEquals("run", item.getAsJsonObject().getAsJsonObject("caller").get("name").getAsString());
            assertEquals("void pick(int n)", item.getAsJsonObject().getAsJsonObject("callee").get("signature").getAsString());
        }
        JsonObject callees = query("find_callees", file, code.indexOf("run()"), object());
        assertEquals(3, callees.get("total").getAsInt());
        assertEquals("void pick(long n)", callees.getAsJsonArray("calls").get(2).getAsJsonObject().getAsJsonObject("callee").get("signature").getAsString());
    }
    public void testConstructorsAndMethodReferences() throws Exception {
        String code = "class Calls { interface Factory { Calls make(); } Calls() {} Calls(int x) { this(); } void run() { new Calls(); Factory f = Calls::new; } }";
        PsiFile file = source(code);
        JsonObject incoming = query("find_callers", file, code.indexOf("Calls() {}"), object());
        assertEquals(incoming.toString(), 3, incoming.get("total").getAsInt());
        JsonObject outgoing = query("find_callees", file, code.indexOf("run()"), object());
        assertEquals(outgoing.toString(), 2, outgoing.get("total").getAsInt());
        assertEquals("constructor_call", outgoing.getAsJsonArray("calls").get(0).getAsJsonObject().get("kind").getAsString());
        assertEquals("method_reference", outgoing.getAsJsonArray("calls").get(1).getAsJsonObject().get("kind").getAsString());
    }
    public void testDeferredEdgesNestedContextsAndUnresolvedCalls() throws Exception {
        String code = "class Calls { interface Action { void go(); } void target() {} void run() { Action a = () -> target(); Action b = this::target; class Local { void nested() { target(); } } missing(); } }";
        PsiFile file = source(code);
        JsonObject result = query("find_callees", file, code.indexOf("run()"), object());
        assertEquals(result.toString(), 2, result.get("total").getAsInt());
        assertEquals(1, result.get("unresolvedCount").getAsInt());
        for (JsonElement item : result.getAsJsonArray("calls")) assertTrue(item.getAsJsonObject().get("deferred").getAsBoolean());
        assertEquals(3, query("find_callers", file, code.indexOf("target() {}"), object()).get("total").getAsInt());
    }
    public void testMethodReferenceQualifierIsAnImmediateSeparateCall() throws Exception {
        String code="class Calls { interface Action { void go(); } Calls factory(){return this;} void target(){} void run(){Action action = factory()::target;} }";
        PsiFile file=source(code);
        JsonObject incoming=query("find_callers",file,code.indexOf("factory(){"),object());assertEquals(incoming.toString(),1,incoming.get("total").getAsInt());
        JsonObject outgoing=query("find_callees",file,code.indexOf("run()"),object());assertEquals(2,outgoing.get("total").getAsInt());
        for(JsonElement item:outgoing.getAsJsonArray("calls")){JsonObject edge=item.getAsJsonObject();assertEquals(edge.getAsJsonObject("callee").get("name").getAsString().equals("target"),edge.get("deferred").getAsBoolean());}
    }
    public void testStablePaginationForMoreThan200CallsAndUtf16() throws Exception {
        String code = "class Calls { void target() {} void run() { /* 😀 */ " + "target(); ".repeat(241) + "} }";
        PsiFile file = source(code);
        for (String direction : List.of("find_callers", "find_callees")) {
            int selected = direction.equals("find_callers") ? code.indexOf("target() {}") : code.indexOf("run()");
            List<Integer> expected = new ArrayList<>();
            for (int at=code.indexOf("target();"); at>=0; at=code.indexOf("target();", at+1)) expected.add(at+1);
            for (int size : new int[]{200,73}) {
                Map<Integer,List<Integer>> pages=new TreeMap<>();
                for (int offset=((240)/size)*size; offset>=0; offset-=size) {
                    JsonObject result=query(direction,file,selected,object("offset",offset,"limit",size));
                    assertEquals(241,result.get("total").getAsInt());
                    assertEquals(offset+size<241,result.get("hasMore").getAsBoolean());
                    List<Integer> columns=new ArrayList<>();
                    for(JsonElement edge:result.getAsJsonArray("calls")) columns.add(edge.getAsJsonObject().getAsJsonObject("location").get("column").getAsInt());
                    pages.put(offset,columns);
                }
                assertEquals(expected,pages.values().stream().flatMap(Collection::stream).toList());
            }
            JsonObject empty=query(direction,file,selected,object("offset",241));
            assertEquals(0,empty.getAsJsonArray("calls").size()); assertFalse(empty.get("hasMore").getAsBoolean());
        }
    }
    public void testRejectsNonMethodAndReflectsCommittedEdits() throws Exception {
        String code="class Calls { void target() {} void run() { target(); } }";
        PsiFile file=source(code);
        JsonObject bad=rpc("find_callers",file,code.indexOf("Calls"),object());
        assertTrue(bad.get("isError").getAsBoolean()); assertTrue(bad.toString().contains("NOT_METHOD"));
        var doc=PsiDocumentManager.getInstance(getProject()).getDocument(file);
        WriteCommandAction.runWriteCommandAction(getProject(),()->doc.setText(code.replace("target();","target(); target();")));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments(); IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        assertEquals(2,query("find_callers",file,code.indexOf("target() {}"),object()).get("total").getAsInt());
    }
    private PsiFile source(String code) { PsiFile f=myFixture.addFileToProject("Calls.java",code);PsiDocumentManager.getInstance(getProject()).commitAllDocuments();IndexingTestUtil.waitUntilIndexesAreReady(getProject());return f; }
    private JsonObject query(String name,PsiFile file,int at,JsonObject args) throws Exception { JsonObject r=rpc(name,file,at,args);assertFalse(r.toString(),r.get("isError").getAsBoolean());return r.getAsJsonObject("structuredContent"); }
    private JsonObject rpc(String name,PsiFile file,int at,JsonObject args) throws Exception {
        var doc=PsiDocumentManager.getInstance(getProject()).getDocument(file);int line=doc.getLineNumber(at);
        args.addProperty("project",getProject().getBasePath());args.addProperty("file",file.getVirtualFile().getPath());args.addProperty("line",line+1);args.addProperty("column",at-doc.getLineStartOffset(line)+1);
        return PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(()->new RpcHandler(new IdeaToolBackend()).handle(object("jsonrpc","2.0","id",1,"method","tools/call","params",object("name",name,"arguments",args)))),30000).getAsJsonObject("result");
    }
}
