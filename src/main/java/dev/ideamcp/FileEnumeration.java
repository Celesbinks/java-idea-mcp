package dev.ideamcp;

import com.google.gson.*;
import com.intellij.mcpserver.toolsets.general.SearchToolsetKt;
import com.intellij.mcpserver.util.Fs_utilKt;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.GlobUtilKt;
import com.intellij.openapi.vfs.*;
import dev.ideamcp.protocol.Arguments;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static dev.ideamcp.protocol.Json.object;

/** Conservative, uncached complete-result path; null always means delegate to the provider. */
final class FileEnumeration {
    private static final long BUDGET = TimeUnit.MILLISECONDS.toNanos(200);

    static JsonObject tryCall(Project project, JsonObject args, long deadline) throws Exception {
        ProgressManager.checkCanceled();
        if (System.nanoTime() >= deadline) return null;
        if (Arguments.bool(args, "includeExcluded", false) || !args.has("paths")) return null;
        List<String> paths = new ArrayList<>();
        List<String> directories = new ArrayList<>();
        for (JsonElement value : args.getAsJsonArray("paths")) {
            String pattern = value.getAsString();
            paths.add(pattern);
            if (pattern.isBlank() || pattern.startsWith("!")) continue;
            if (!pattern.endsWith("/**")) return null;
            String directory = pattern.substring(0, pattern.length() - 3);
            if (directory.isEmpty() || !directory.equals(directory.trim())) return null;
            for (String segment : directory.split("/", -1)) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return null;
                for (char c : segment.toCharArray()) if ("!\\:*?[]{}".indexOf(c) >= 0) return null;
            }
            directories.add(directory);
        }
        if (directories.isEmpty()) return null;
        if (args.get("q").getAsString().isBlank()) return null;
        if (args.has("limit")) {
            try { if (args.get("limit").getAsBigDecimal().intValueExact() <= 0) return null; }
            catch (ArithmeticException invalid) { return null; }
        }
        return JavaCodeTools.read(project, () -> {
            try { return enumerate(project, args, paths, directories, Math.min(deadline, System.nanoTime() + BUDGET)); }
            catch (com.intellij.mcpserver.McpExpectedError | java.util.regex.PatternSyntaxException invalid) { return null; }
        },
                Math.max(1, deadline - System.nanoTime()));
    }

    private static JsonObject enumerate(Project project, JsonObject args, List<String> paths, List<String> directories, long deadline) throws com.intellij.mcpserver.McpExpectedError {
        if (project.isDisposed() || DumbService.isDumb(project)) return null;
        Path base = Fs_utilKt.getProjectDirectory(project);
        String query = args.get("q").getAsString();
        String normalized = SearchToolsetKt.normalizeGlobPattern(query, base, query);
        // Keep directory-root resolution semantics in the provider for all other q shapes.
        if (!normalized.startsWith("**/") || normalized.substring(3).contains("/")) return null;
        var matcher = GlobUtilKt.getPathMatcher(normalized, false);
        var scope = SearchToolsetKt.buildPathScope(base, paths);
        int limit = SearchToolsetKt.normalizeLimit(Arguments.integer(args, "limit", 1000, Integer.MIN_VALUE, Integer.MAX_VALUE));
        var index = ProjectFileIndex.getInstance(project);
        List<VirtualFile> roots = new ArrayList<>();
        for (String directory : directories) {
            ProgressManager.checkCanceled();
            if (System.nanoTime() >= deadline) return null;
            VirtualFile root = LocalFileSystem.getInstance().findFileByNioFile(base.resolve(directory));
            if (root == null || !root.isDirectory() || !index.isInContent(root)) return null;
            for (VirtualFile parent = root; parent != null; parent = parent.getParent())
                if (parent.is(VFileProperty.SYMLINK)) return null;
            if (roots.stream().noneMatch(existing -> VfsUtilCore.isAncestor(existing, root, false))) {
                roots.removeIf(existing -> VfsUtilCore.isAncestor(root, existing, false));
                roots.add(root);
            }
        }
        Set<String> seen = new HashSet<>();
        JsonArray items = new JsonArray();
        for (VirtualFile root : roots) {
            boolean complete = index.iterateContentUnderDirectory(root, file -> {
                ProgressManager.checkCanceled();
                if (System.nanoTime() >= deadline) return false;
                if (file.isDirectory()) return true;
                Path absolute = VirtualFileUtil.toNioPathOrNull(file);
                if (absolute == null || !absolute.startsWith(base)) return true;
                Path relative = base.relativize(absolute);
                // LocalFileSystem candidates already use the default NIO filesystem,
                // exactly the identity branch of the provider's toGlobPath helper.
                if (!matcher.matches(relative) || (scope != null && !scope.matches(relative))) return true;
                if (seen.add(relative.toString())) items.add(object("filePath", relative.toString()));
                // The SDK marks more=true even for exactly limit results. Preserve its prefix.
                return items.size() < limit;
            });
            if (!complete) return null;
        }
        if (System.nanoTime() >= deadline) return null;
        JsonObject data = object("items", items);
        return object("content", new Object[]{object("type", "text", "text", data.toString())}, "structuredContent", data, "isError", false);
    }
}
