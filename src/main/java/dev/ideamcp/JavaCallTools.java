package dev.ideamcp;

import com.google.gson.*;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import dev.ideamcp.protocol.Arguments;
import java.util.*;
import static dev.ideamcp.protocol.Json.object;

/** One-hop static Java calls, resolved by PSI rather than by method-name matching. */
final class JavaCallTools {
    private record Key(String url, int start, int end) {}
    private record Edge(PsiElement site, TextRange range, PsiMethod caller, PsiMethod callee, String kind, boolean deferred) {}
    static JsonObject calls(Project project, JsonObject args, boolean incoming) {
        List<PsiElement> targets = JavaCodeTools.targets(project, args);
        if (targets.size() != 1) throw new IllegalArgumentException("AMBIGUOUS: position resolves to multiple symbols");
        if (!(targets.getFirst() instanceof PsiMethod target)) throw new IllegalArgumentException("NOT_METHOD: select a Java method or constructor");
        NavigableMap<Key, Edge> edges = new TreeMap<>(Comparator.comparing(Key::url).thenComparingInt(Key::start).thenComparingInt(Key::end));
        int[] unresolved = {0};
        if (incoming) {
            MethodReferencesSearch.search(target, GlobalSearchScope.projectScope(project), true).forEach(reference -> {
                ProgressManager.checkCanceled();
                PsiElement element = reference.getElement();
                PsiMethodReferenceExpression methodRef = element instanceof PsiMethodReferenceExpression ref ? ref : null;
                PsiCall call = methodRef == null ? PsiTreeUtil.getParentOfType(element, PsiCall.class, false) : null;
                PsiMethod resolved = methodRef != null && methodRef.resolve() instanceof PsiMethod m ? m : call == null ? null : call.resolveMethod();
                if (resolved != null && PsiManager.getInstance(project).areElementsEquivalent(target, resolved)) {
                    TextRange range = reference.getRangeInElement().shiftRight(element.getTextRange().getStartOffset());
                    put(edges, element, range, enclosingMethod(element), target, methodRef != null ? "method_reference" : resolved.isConstructor() ? "constructor_call" : "method_call", methodRef != null || insideLambda(element));
                }
                return true;
            });
        } else if (target.getBody() != null) {
            target.getBody().accept(new JavaRecursiveElementWalkingVisitor() {
                @Override public void visitClass(PsiClass aClass) { /* Separate execution context: query its own methods. */ }
                @Override public void visitElement(PsiElement element) {
                    ProgressManager.checkCanceled();
                    if (element instanceof PsiCall call && !(call instanceof PsiNewExpression n && (n.getArrayDimensions().length > 0 || n.getArrayInitializer() != null))) {
                        PsiMethod resolved = call.resolveMethod();
                        if (resolved == null) unresolved[0]++;
                        else put(edges, element, siteRange(element), target, resolved, resolved.isConstructor() ? "constructor_call" : "method_call", insideLambda(element));
                    } else if (element instanceof PsiMethodReferenceExpression ref) {
                        if (ref.resolve() instanceof PsiMethod resolved) put(edges, element, siteRange(element), target, resolved, "method_reference", true);
                        else unresolved[0]++;
                    }
                    super.visitElement(element);
                }
            });
        }
        int offset = Arguments.integer(args, "offset", 0, 0, 1000000), limit = Arguments.integer(args, "limit", 100, 1, 200);
        JsonArray items = new JsonArray();
        int index = 0;
        for (Edge edge : edges.values()) {
            ProgressManager.checkCanceled();
            if (index++ < offset) continue;
            if (items.size() == limit) break;
            items.add(object("caller", edge.caller == null ? null : JavaCodeTools.symbol(edge.caller), "callee", JavaCodeTools.symbol(edge.callee),
                    "location", JavaCodeTools.location(edge.site, edge.range), "kind", edge.kind, "deferred", edge.deferred));
        }
        boolean more = offset + items.size() < edges.size();
        return object("target", JavaCodeTools.symbol(target), "direction", incoming ? "incoming" : "outgoing", "calls", items, "total", edges.size(),
                "offset", offset, "limit", limit, "hasMore", more, "nextOffset", more ? offset + items.size() : null, "unresolvedCount", unresolved[0],
                "analysis", "one-hop static resolution; no runtime dispatch expansion; lambda and method-reference edges are deferred; nested class bodies and implicit calls are excluded",
                "order", "file URL, numeric start and end offsets; stable while source and indices are unchanged");
    }
    private static void put(Map<Key, Edge> edges, PsiElement site, TextRange range, PsiMethod caller, PsiMethod callee, String kind, boolean deferred) {
        PsiFile file = site.getContainingFile();
        if (file != null && file.getVirtualFile() != null) edges.putIfAbsent(new Key(file.getVirtualFile().getUrl(), range.getStartOffset(), range.getEndOffset()), new Edge(site, range, caller, callee, kind, deferred));
    }
    private static TextRange siteRange(PsiElement element) {
        PsiElement name = element instanceof PsiMethodCallExpression c ? c.getMethodExpression().getReferenceNameElement()
                : element instanceof PsiMethodReferenceExpression r ? r.getReferenceNameElement()
                : element instanceof PsiNewExpression n && n.getClassReference() != null ? n.getClassReference().getReferenceNameElement() : null;
        return name == null ? element.getTextRange() : name.getTextRange();
    }
    private static PsiMethod enclosingMethod(PsiElement site) {
        for (PsiElement p = site.getParent(); p != null; p = p.getParent()) {
            if (p instanceof PsiMethod method) return method;
            if (p instanceof PsiClass || p instanceof PsiField || p instanceof PsiClassInitializer) return null;
        }
        return null;
    }
    private static boolean insideLambda(PsiElement site) {
        for (PsiElement p = site.getParent(); p != null && !(p instanceof PsiMethod) && !(p instanceof PsiClass); p = p.getParent()) if (p instanceof PsiLambdaExpression) return true;
        return false;
    }
}
