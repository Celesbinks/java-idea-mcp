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
import java.util.*;
import static dev.ideamcp.protocol.Json.object;

/** Complete expected reference locations are generated independently of ReferencesSearch. */
public final class ReferencePaginationTest extends JavaCodeInsightFixtureTestCase {
    private static final String NAME = "PagingTarget";
    private static final Comparator<Position> ORDER = Comparator.comparing(Position::url).thenComparingInt(Position::line)
            .thenComparingInt(Position::column).thenComparingInt(Position::endLine).thenComparingInt(Position::endColumn);
    @Override protected void tuneFixture(JavaModuleFixtureBuilder moduleBuilder) throws Exception {
        super.tuneFixture(moduleBuilder);
        moduleBuilder.addJdk(System.getProperty("java.home"));
        moduleBuilder.setLanguageLevel(LanguageLevel.JDK_21);
    }
    public void testHundredsOfReferencesAreCompleteAndStableAcrossPageSizes() throws Exception {
        PsiFile target = target();
        List<Position> expected = new ArrayList<>();
        // Reverse creation order so expected file ordering is independent of insertion/index order.
        for (int file = 36; file >= 0; file--) {
            StringBuilder source = new StringBuilder("package pagination;\nclass Use" + file + " {\n");
            for (int field = 0; field < 13; field++) source.append("    PagingTarget field").append(field).append(";\n");
            source.append("}\n");
            PsiFile usage = myFixture.addFileToProject("pagination/Use%02d.java".formatted(file), source.toString());
            expected.addAll(positions(usage));
        }
        commitAndIndex();
        expected.sort(ORDER);
        assertEquals(481, expected.size());
        for (int round = 0; round < 3; round++) assertPages(target, expected, 200, false, round % 2 != 0);
        assertPages(target, expected, 137, false, true);
        assertPages(target, expected, 73, false, false);
        List<Position> declared = new ArrayList<>();
        declared.add(positions(target).getFirst()); declared.addAll(expected);
        assertPages(target, declared, 200, true, true);
    }
    public void testExactBoundaryEmptyOffsetsAndDeclarationPagination() throws Exception {
        PsiFile target = target();
        StringBuilder source = new StringBuilder("package pagination; class Uses { /* 中文 😀 */ ");
        for (int i = 0; i < 200; i++) source.append("PagingTarget field").append(i).append("; ");
        source.append("}");
        PsiFile usage = myFixture.addFileToProject("pagination/Uses.java", source.toString());
        commitAndIndex();
        List<Position> expected = positions(usage);
        expected.sort(ORDER);
        assertPages(target, expected, 200, false, false);
        for (int offset : new int[]{200, 1000000}) {
            JsonObject empty = query(target, offset, 200, false);
            assertEquals(200, empty.get("total").getAsInt());
            assertEquals(0, empty.getAsJsonArray("references").size());
            assertFalse(empty.get("hasMore").getAsBoolean());
            assertTrue(empty.get("nextOffset").isJsonNull());
        }
        List<Position> declared = new ArrayList<>(); declared.add(positions(target).getFirst()); declared.addAll(expected);
        assertPages(target, declared, 200, true, false);
        JsonObject declaration = query(target, 0, 1, true);
        assertTrue(declaration.getAsJsonArray("references").get(0).getAsJsonObject().get("declaration").getAsBoolean());
        assertEquals(positions(target).getFirst(), position(declaration.getAsJsonArray("references").get(0).getAsJsonObject().getAsJsonObject("location")));
        assertEquals(expected.getFirst(), position(query(target, 1, 1, true).getAsJsonArray("references").get(0).getAsJsonObject().getAsJsonObject("location")));
    }
    public void testCommittedEditsAreReflectedWithoutStalePages() throws Exception {
        PsiFile target = target();
        PsiFile usage = myFixture.addFileToProject("pagination/Uses.java", "package pagination; class Uses { PagingTarget a; }");
        commitAndIndex();
        assertPages(target, positions(usage), 200, false, false);
        var documents = PsiDocumentManager.getInstance(getProject());
        var document = documents.getDocument(usage);
        assertNotNull(document);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText("package pagination; class Uses { PagingTarget a; PagingTarget b; }"));
        commitAndIndex();
        assertPages(target, positions(usage), 1, false, true);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText("package pagination; class Uses { PagingTarget b; }"));
        commitAndIndex();
        assertPages(target, positions(usage), 200, false, false);
    }
    private PsiFile target() { return myFixture.addFileToProject("pagination/PagingTarget.java", "package pagination;\npublic class PagingTarget {}\n"); }
    private void commitAndIndex() {
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
    }
    private void assertPages(PsiFile target, List<Position> expected, int limit, boolean includeDeclaration, boolean reverse) throws Exception {
        List<Integer> offsets = new ArrayList<>();
        for (int offset = 0; offset < expected.size(); offset += limit) offsets.add(offset);
        if (reverse) Collections.reverse(offsets);
        Map<Integer, List<Position>> pages = new TreeMap<>();
        for (int offset : offsets) {
            JsonObject result = query(target, offset, limit, includeDeclaration);
            JsonArray references = result.getAsJsonArray("references");
            int end = Math.min(expected.size(), offset + limit);
            assertEquals(expected.size(), result.get("total").getAsInt());
            assertEquals(end - offset, references.size());
            assertEquals(end < expected.size(), result.get("hasMore").getAsBoolean());
            if (end < expected.size()) assertEquals(end, result.get("nextOffset").getAsInt());
            else assertTrue(result.get("nextOffset").isJsonNull());
            List<Position> actual = new ArrayList<>();
            for (JsonElement element : references) {
                JsonObject reference = element.getAsJsonObject();
                assertEquals(includeDeclaration && offset == 0 && actual.isEmpty(), reference.get("declaration").getAsBoolean());
                actual.add(position(reference.getAsJsonObject("location")));
            }
            assertEquals(expected.subList(offset, end), actual);
            pages.put(offset, actual);
        }
        List<Position> joined = pages.values().stream().flatMap(Collection::stream).toList();
        assertEquals(expected, joined);
        assertEquals("No duplicate complete source ranges", joined.size(), new HashSet<>(joined).size());
    }
    private JsonObject query(PsiFile target, int offset, int limit, boolean includeDeclaration) throws Exception {
        Position declaration = positions(target).getFirst();
        JsonObject args = object("project", getProject().getBasePath(), "file", target.getVirtualFile().getPath(), "line", declaration.line(), "column", declaration.column(),
                "offset", offset, "limit", limit, "includeDeclaration", includeDeclaration);
        var future = AppExecutorUtil.getAppExecutorService().submit(() -> new RpcHandler(new JavaCodeTools()::call).handle(object("jsonrpc", "2.0", "id", 1,
                "method", "tools/call", "params", object("name", "find_references", "arguments", args))));
        JsonObject result = PlatformTestUtil.waitForFuture(future, 30000).getAsJsonObject("result");
        assertFalse(result.toString(), result.get("isError").getAsBoolean());
        return result.getAsJsonObject("structuredContent");
    }
    private List<Position> positions(PsiFile file) {
        String text = file.getText();
        List<Position> result = new ArrayList<>();
        for (int offset = text.indexOf(NAME); offset >= 0; offset = text.indexOf(NAME, offset + NAME.length())) {
            int line = 1;
            for (int i = 0; i < offset; i++) if (text.charAt(i) == '\n') line++;
            int column = offset - text.lastIndexOf('\n', offset - 1);
            result.add(new Position(file.getVirtualFile().getUrl(), line, column, line, column + NAME.length()));
        }
        return result;
    }
    private static Position position(JsonObject location) { return new Position(location.get("url").getAsString(), location.get("line").getAsInt(), location.get("column").getAsInt(), location.get("endLine").getAsInt(), location.get("endColumn").getAsInt()); }
    private record Position(String url, int line, int column, int endLine, int endColumn) {}
}
