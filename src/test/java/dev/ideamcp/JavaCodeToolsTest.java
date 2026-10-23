package dev.ideamcp;

import com.google.gson.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.*;
import com.intellij.testFramework.*;
import com.intellij.testFramework.builders.JavaModuleFixtureBuilder;
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase;
import com.intellij.util.concurrency.AppExecutorUtil;
import dev.ideamcp.protocol.RpcHandler;
import static dev.ideamcp.protocol.Json.object;

/** Real Java PSI/index integration tests, including overloads and editor documents. */
public final class JavaCodeToolsTest extends JavaCodeInsightFixtureTestCase {
    @Override protected void tuneFixture(JavaModuleFixtureBuilder moduleBuilder) throws Exception {
        super.tuneFixture(moduleBuilder);
        moduleBuilder.addJdk(System.getProperty("java.home"));
        moduleBuilder.setLanguageLevel(LanguageLevel.JDK_21);
    }
    public void testReadsDeclarationsAndPaginates() throws Exception {
        PsiFile file = source("Sample.java", """
                package demo;
                class Sample {
                    int field;
                    Sample() {}
                    int pick(int x) { return x; }
                    long pick(long x) { return x; }
                    interface Nested {}
                }
                """);
        JsonObject result = query("read_symbols", object("file", file.getVirtualFile().getPath(), "limit", 2));
        JsonArray symbols = result.getAsJsonArray("symbols");
        assertEquals(2, symbols.size());
        assertEquals("class", symbols.get(0).getAsJsonObject().get("kind").getAsString());
        assertEquals("demo.Sample", symbols.get(0).getAsJsonObject().get("qualifiedName").getAsString());
        assertEquals("field", symbols.get(1).getAsJsonObject().get("kind").getAsString());
        assertTrue(result.get("hasMore").getAsBoolean());
        result = query("read_symbols", object("file", file.getVirtualFile().getPath(), "offset", 2));
        assertEquals(4, result.getAsJsonArray("symbols").size());
        assertFalse(result.get("hasMore").getAsBoolean());
        result = query("read_symbols", object("query", "pick"));
        assertEquals(2, result.getAsJsonArray("symbols").size());
        result = query("read_symbols", object("query", "demo.Sample"));
        assertEquals(1, result.getAsJsonArray("symbols").size());
    }
    public void testResolvesOverloadAndFindsOnlySemanticReferences() throws Exception {
        String code = """
                package demo;
                class Sample {
                    int pick(int x) { return x; }
                    long pick(long x) { return x; }
                    void run() {
                        pick(1);
                        pick(2);
                        pick(3L);
                        // pick(99) is only a comment
                    }
                }
                """;
        PsiFile file = source("Sample.java", code);
        JsonObject position = position(file, code.indexOf("pick(1)"));
        JsonObject result = query("find_definition", position);
        JsonObject target = result.getAsJsonArray("definitions").get(0).getAsJsonObject();
        assertEquals("int pick(int x)", target.get("signature").getAsString());
        assertEquals(3, target.getAsJsonObject("location").get("line").getAsInt());
        result = query("find_references", position);
        assertEquals(2, result.getAsJsonArray("references").size());
        position.addProperty("includeDeclaration", true);
        result = query("find_references", position);
        assertEquals(3, result.getAsJsonArray("references").size());
        JsonObject page = position.deepCopy(); page.addProperty("limit", 1);
        result = query("find_references", page);
        assertTrue(result.get("hasMore").getAsBoolean());
        page.addProperty("offset", 2);
        result = query("find_references", page);
        assertEquals(1, result.getAsJsonArray("references").size());
        assertFalse(result.get("hasMore").getAsBoolean());
    }
    public void testLocalVariableAndUtf16Position() throws Exception {
        String code = "class Sample { void run() { int count = 1; /* 中文 😀 */ count++; } }";
        PsiFile file = source("Sample.java", code);
        JsonObject result = query("find_definition", position(file, code.indexOf("count++")));
        JsonObject target = result.getAsJsonArray("definitions").get(0).getAsJsonObject();
        assertEquals("local_variable", target.get("kind").getAsString());
        assertEquals(code.indexOf("count =") + 1, target.getAsJsonObject("location").get("column").getAsInt());
        result = query("find_references", position(file, code.indexOf("count =")));
        assertEquals(1, result.getAsJsonArray("references").size());
    }
    public void testWhitespaceDoesNotResolveToEnclosingMethod() throws Exception {
        String code = "class Sample { void run() {    } }";
        PsiFile file = source("Sample.java", code);
        JsonObject result = rpc("find_definition", position(file, code.indexOf("    ") + 1));
        assertTrue(result.get("isError").getAsBoolean());
        assertTrue(result.toString().contains("UNRESOLVED"));
    }
    public void testUnsavedCommittedEditorContents() throws Exception {
        PsiFile file = source("Sample.java", "class Sample { int before; }");
        Document document = PsiDocumentManager.getInstance(getProject()).getDocument(file);
        assertNotNull(document);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText("class Sample { int after; }"));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        JsonObject result = query("read_symbols", object("file", file.getVirtualFile().getPath()));
        assertEquals("after", result.getAsJsonArray("symbols").get(1).getAsJsonObject().get("name").getAsString());
    }
    public void testRejectsNonJavaAndOutOfRangePosition() throws Exception {
        PsiFile java = source("Sample.java", "class Sample {}");
        JsonObject position = position(java, 0); position.addProperty("line", 99);
        assertTrue(rpc("find_definition", position).get("isError").getAsBoolean());
        PsiFile text = myFixture.addFileToProject("notes.txt", "class Pretend {}");
        JsonObject result = rpc("read_symbols", object("file", text.getVirtualFile().getPath()));
        assertTrue(result.get("isError").getAsBoolean());
        assertTrue(result.toString().contains("NOT_JAVA"));
    }
    private PsiFile source(String name, String code) {
        PsiFile file = myFixture.addFileToProject(name, code);
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        return file;
    }
    private JsonObject position(PsiFile file, int offset) {
        Document document = PsiDocumentManager.getInstance(getProject()).getDocument(file);
        int line = document.getLineNumber(offset);
        return object("file", file.getVirtualFile().getPath(), "line", line + 1, "column", offset - document.getLineStartOffset(line) + 1);
    }
    private JsonObject query(String name, JsonObject args) throws Exception {
        JsonObject result = rpc(name, args);
        assertFalse(result.toString(), result.get("isError").getAsBoolean());
        return result.getAsJsonObject("structuredContent");
    }
    private JsonObject rpc(String name, JsonObject args) throws Exception {
        args.addProperty("project", getProject().getBasePath());
        var future = AppExecutorUtil.getAppExecutorService().submit(() -> new RpcHandler(new JavaCodeTools()::call).handle(
                object("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", object("name", name, "arguments", args))));
        return PlatformTestUtil.waitForFuture(future, 30000).getAsJsonObject("result");
    }
}
