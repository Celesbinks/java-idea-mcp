package dev.ideamcp;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.ideamcp.protocol.Json;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.*;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;

/**
 * Pure configuration merge/CAS engine. Never logs a configuration or a credential.
 */
public final class ClientConfiguration {
    public enum Format {CODEX_TOML, MCP_JSON, VSCODE_JSON, WINDSURF_JSON, STDIO_JSON}

    public record Target(String name, Path file, Format format) {
    }

    public record Launcher(String command, List<String> args) {
        public Launcher {
            args = List.copyOf(args);
        }
    }

    public record Connection(String url, String token, Launcher launcher) {
        public Connection(String url, String token) {
            this(url, token, null);
        }

        public Connection {
            URI u = URI.create(url);
            if (!"http".equals(u.getScheme()) || !"127.0.0.1".equals(u.getHost()) || u.getPort() < 1 || !"/mcp".equals(u.getPath()) || u.getUserInfo() != null || u.getQuery() != null || u.getFragment() != null)
                throw new IllegalArgumentException("Expected a loopback MCP URL");
            TokenStore.validate(token);
        }

        @Override
        public String toString() {
            return "Connection[redacted]";
        }
    }

    public record Update(String digest, boolean changed) {
    }

    private ClientConfiguration() {
    }

    public static List<Target> detect(Path home, Path roaming, Path project) {
        List<Target> targets = new ArrayList<>();
        add(targets, "Codex", home.resolve(".codex/config.toml"), Format.CODEX_TOML);
        add(targets, "Claude Desktop", roaming.resolve("Claude/claude_desktop_config.json"), Format.STDIO_JSON);
        if (Files.exists(home.resolve(".claude.json")) || Files.isDirectory(home.resolve(".claude")))
            targets.add(new Target("Claude Code", home.resolve(".claude.json").toAbsolutePath().normalize(), Format.MCP_JSON));
        add(targets, "Cursor", home.resolve(".cursor/mcp.json"), Format.MCP_JSON);
        add(targets, "Windsurf", home.resolve(".codeium/windsurf/mcp_config.json"), Format.WINDSURF_JSON);
        if (project != null) {
            add(targets, "Codex (project)", project.resolve(".codex/config.toml"), Format.CODEX_TOML);
            add(targets, "Claude Code (project)", project.resolve(".mcp.json"), Format.MCP_JSON);
            add(targets, "VS Code / Copilot (project)", project.resolve(".vscode/mcp.json"), Format.VSCODE_JSON);
        }
        return List.copyOf(targets);
    }

    private static void add(List<Target> list, String name, Path path, Format format) {
        if (Files.exists(path) || Files.isDirectory(path.getParent()))
            list.add(new Target(name, path.toAbsolutePath().normalize(), format));
    }

    /**
     * expectedDigest null means explicit first registration, only an absent or matching entry is adopted.
     */
    public static Update update(Target target, Connection connection, String expectedDigest) throws IOException {
        Path path = target.file().toAbsolutePath().normalize();
        if (Files.isSymbolicLink(path)) throw new IOException("CONFIG_SYMLINK: " + path);
        Files.createDirectories(path.getParent());
        try (FileChannel channel = FileChannel.open(path.resolveSibling(path.getFileName() + ".idea-java.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("CONFIG_BUSY: " + path);
            String before = Files.exists(path) ? Files.readString(path) : "";
            String owned = owned(target.format(), before), fingerprint = owned == null ? null : digest(owned);
            if (expectedDigest != null && !expectedDigest.equals(fingerprint))
                throw new IOException("CONFIG_CONFLICT: managed entry changed in " + path);
            if (expectedDigest == null && owned != null && !matches(target.format(), before, connection))
                throw new IOException("CONFIG_CONFLICT: idea-java already belongs to another connection in " + path);
            String after = merge(target.format(), before, connection);
            if (!matches(target.format(), after, connection))
                throw new IOException("CONFIG_FORMAT: unable to update the managed connection safely");
            String next = digest(Objects.requireNonNull(owned(target.format(), after)));
            if (after.equals(before)) return new Update(next, false);
            Path temp = Files.createTempFile(path.getParent(), ".idea-java-", ".tmp");
            try {
                if (Files.exists(path) && Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class) != null)
                    Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(path));
                if (Files.exists(path)) {
                    var acl = Files.getFileAttributeView(path, java.nio.file.attribute.AclFileAttributeView.class);
                    if (acl != null)
                        Files.getFileAttributeView(temp, java.nio.file.attribute.AclFileAttributeView.class).setAcl(acl.getAcl());
                }
                Files.writeString(temp, after, StandardCharsets.UTF_8);
                String current = Files.exists(path) ? Files.readString(path) : "";
                if (!before.equals(current))
                    throw new IOException("CONFIG_CONFLICT: configuration changed while preparing update in " + path);
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return new Update(next, true);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (java.nio.channels.OverlappingFileLockException e) {
            throw new IOException("CONFIG_BUSY: " + path);
        }
    }

    static String merge(Format format, String input, Connection connection) throws IOException {
        if (format == Format.CODEX_TOML) return mergeToml(input, connection);
        JsonObject root = parseJson(input), servers = container(root, format == Format.VSCODE_JSON ? "servers" : "mcpServers");
        JsonObject entry = servers.has("idea-java") ? servers.getAsJsonObject("idea-java").deepCopy() : new JsonObject();
        if (format == Format.STDIO_JSON) {
            if (connection.launcher() == null) throw new IOException("CONFIG_BRIDGE_UNAVAILABLE");
            entry.addProperty("command", connection.launcher().command());
            entry.add("args", Json.GSON.toJsonTree(connection.launcher().args()));
            JsonObject env = container(entry, "env");
            env.addProperty("IDEA_JAVA_MCP_URL", connection.url());
            env.addProperty("IDEA_JAVA_MCP_TOKEN", connection.token());
        } else {
            if (entry.has("command"))
                throw new IOException("CONFIG_CONFLICT: an existing stdio entry cannot be adopted");
            entry.addProperty(format == Format.WINDSURF_JSON ? "serverUrl" : "url", connection.url());
            entry.addProperty("type", "http");
            JsonObject headers = container(entry, "headers");
            headers.addProperty("Authorization", "Bearer " + connection.token());
        }
        servers.add("idea-java", entry);
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n";
    }

    private static JsonObject container(JsonObject root, String key) throws IOException {
        if (root.has(key)) {
            if (!root.get(key).isJsonObject()) throw new IOException("CONFIG_INVALID: " + key + " must be an object");
            return root.getAsJsonObject(key);
        }
        JsonObject result = new JsonObject();
        root.add(key, result);
        return result;
    }

    private static JsonObject parseJson(String input) throws IOException {
        if (input.isBlank()) return new JsonObject();
        try {
            JsonReader reader = new JsonReader(new StringReader(input));
            reader.setStrictness(Strictness.LENIENT);
            JsonElement result = readJson(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT || !result.isJsonObject())
                throw new JsonParseException("Invalid configuration");
            return result.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("CONFIG_INVALID: invalid JSON client configuration");
        }
    }

    private static JsonElement readJson(JsonReader reader, int depth) throws IOException {
        if (depth > 64) throw new IOException("CONFIG_INVALID: configuration nesting is too deep");
        if (reader.peek() == JsonToken.BEGIN_OBJECT) {
            JsonObject value = new JsonObject();
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (value.has(key)) throw new IOException("CONFIG_INVALID: duplicate JSON key");
                value.add(key, readJson(reader, depth + 1));
            }
            reader.endObject();
            return value;
        }
        if (reader.peek() == JsonToken.BEGIN_ARRAY) {
            JsonArray value = new JsonArray();
            reader.beginArray();
            while (reader.hasNext()) value.add(readJson(reader, depth + 1));
            reader.endArray();
            return value;
        }
        return JsonParser.parseReader(reader);
    }

    private static TomlParseResult parseToml(String input) throws IOException {
        TomlParseResult parsed = Toml.parse(input);
        if (parsed.hasErrors()) throw new IOException("CONFIG_INVALID: invalid TOML client configuration");
        return parsed;
    }

    private static TomlTable entry(TomlParseResult parsed) throws IOException {
        try {
            TomlTable servers = parsed.getTable("mcp_servers");
            return servers == null ? null : servers.getTable("idea-java");
        } catch (RuntimeException e) {
            throw new IOException("CONFIG_INVALID: mcp_servers.idea-java must be a table");
        }
    }

    private static boolean matches(Format format, String input, Connection connection) throws IOException {
        if (format == Format.CODEX_TOML) {
            TomlTable e = entry(parseToml(input));
            if (e == null) return false;
            return Objects.equals(e.getString("url"), connection.url()) && Objects.equals(e.getString("http_headers.Authorization"), "Bearer " + connection.token()) && !e.contains("command");
        }
        JsonObject root = parseJson(input), servers = container(root, format == Format.VSCODE_JSON ? "servers" : "mcpServers");
        JsonObject e = servers.getAsJsonObject("idea-java");
        if (e == null) return false;
        if (format == Format.STDIO_JSON)
            return connection.launcher() != null && e.has("command") && connection.launcher().command().equals(e.get("command").getAsString()) && Json.GSON.toJsonTree(connection.launcher().args()).equals(e.get("args")) && e.has("env") && Objects.equals(e.getAsJsonObject("env").get("IDEA_JAVA_MCP_URL"), new JsonPrimitive(connection.url())) && Objects.equals(e.getAsJsonObject("env").get("IDEA_JAVA_MCP_TOKEN"), new JsonPrimitive(connection.token()));
        String key = format == Format.WINDSURF_JSON ? "serverUrl" : "url";
        return !e.has("command") && e.has(key) && connection.url().equals(e.get(key).getAsString()) && e.has("headers") && e.getAsJsonObject("headers").has("Authorization") && ("Bearer " + connection.token()).equals(e.getAsJsonObject("headers").get("Authorization").getAsString());
    }

    private static String owned(Format format, String input) throws IOException {
        if (format == Format.CODEX_TOML) {
            if (entry(parseToml(input)) == null) return null;
            return tomlSections(input).stream().filter(s -> s.name.equals("mcp_servers.idea-java") || s.name.startsWith("mcp_servers.idea-java.")).map(Section::text).reduce("", String::concat);
        }
        JsonObject root = parseJson(input);
        JsonElement servers = root.get(format == Format.VSCODE_JSON ? "servers" : "mcpServers");
        if (servers == null) return null;
        if (!servers.isJsonObject()) throw new IOException("CONFIG_INVALID: server container must be an object");
        JsonElement e = servers.getAsJsonObject().get("idea-java");
        if (e == null) return null;
        if (!e.isJsonObject()) throw new IOException("CONFIG_INVALID: idea-java must be an object");
        return e.toString();
    }

    private record Section(String name, String text) {
    }

    private static List<Section> tomlSections(String input) throws IOException {
        List<Section> result = new ArrayList<>();
        String current = "";
        StringBuilder body = new StringBuilder();
        String multiline = null;
        for (String line : input.split("(?<=\n)", -1)) {
            Matcher header = Pattern.compile("^\\s*\\[([^\\[\\]]+)\\]\\s*(?:#.*)?$").matcher(line.stripTrailing());
            if (multiline == null && line.stripLeading().startsWith("[[")) {
                result.add(new Section(current, body.toString()));
                body.setLength(0);
                current = "<unmanaged-array>";
            }
            if (multiline == null && header.matches()) {
                result.add(new Section(current, body.toString()));
                body.setLength(0);
                current = sectionName(header.group(1));
            }
            body.append(line);
            // Do not mistake a section-looking line inside a TOML multiline string for a header.
            multiline = multilineState(line, multiline);
        }
        result.add(new Section(current, body.toString()));
        return result;
    }

    private static String sectionName(String header) throws IOException {
        TomlTable table = parseToml("[" + header + "]\n");
        List<String> keys = new ArrayList<>();
        while (table.keySet().size() == 1) {
            String key = table.keySet().iterator().next();
            keys.add(key);
            table = table.getTable(List.of(key));
            if (table == null) break;
        }
        // A quoted key containing a dot is one key, never a managed dotted table.
        return keys.stream().anyMatch(k -> k.contains(".")) ? "<unmanaged>" : String.join(".", keys);
    }

    private static String multilineState(String line, String active) {
        boolean basic = false, literal = false, escape = false;
        for (int i = 0; i < line.length(); i++) {
            if (active != null) {
                if (active.equals("\"\"\"") && line.charAt(i) == '\\') {
                    i++;
                    continue;
                }
                if (line.startsWith(active, i)) {
                    i += 2;
                    active = null;
                }
                continue;
            }
            char c = line.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (basic && c == '\\') {
                escape = true;
                continue;
            }
            if (!basic && !literal && c == '#') break;
            if (!basic && !literal && (line.startsWith("\"\"\"", i) || line.startsWith("'''", i))) {
                active = line.substring(i, i + 3);
                i += 2;
                continue;
            }
            if (c == '\"' && !literal) basic = !basic;
            else if (c == '\'' && !basic) literal = !literal;
        }
        return active;
    }

    private static String mergeToml(String input, Connection c) throws IOException {
        TomlTable existing = entry(parseToml(input));
        if (existing != null && (existing.contains("command") || existing.contains("bearer_token_env_var") || existing.contains("env_http_headers") || existing.contains("http_headers_helper")))
            throw new IOException("CONFIG_CONFLICT: incompatible authentication or transport in idea-java");
        List<Section> sections = tomlSections(input);
        StringBuilder output = new StringBuilder();
        boolean main = false, headers = false;
        for (Section s : sections) {
            String text = s.text;
            if (s.name.equals("mcp_servers.idea-java")) {
                main = true;
                text = assignment(text, "url", quote(c.url()));
                if (Pattern.compile("(?m)^\\s*http_headers\\s*=").matcher(text).find())
                    throw new IOException("CONFIG_FORMAT: expand inline http_headers before automatic configuration");
            }
            if (s.name.equals("mcp_servers.idea-java.http_headers")) {
                headers = true;
                text = assignment(text, "Authorization", quote("Bearer " + c.token()));
            }
            output.append(text);
        }
        if (existing != null && !main)
            throw new IOException("CONFIG_FORMAT: use an explicit mcp_servers.idea-java table");
        if (!main) output.append("\n[mcp_servers.idea-java]\nurl = ").append(quote(c.url())).append('\n');
        if (!headers)
            output.append("\n[mcp_servers.idea-java.http_headers]\nAuthorization = ").append(quote("Bearer " + c.token())).append('\n');
        String result = output.toString();
        parseToml(result);
        return result;
    }

    private static String assignment(String section, String key, String value) throws IOException {
        Pattern p = Pattern.compile("^([\\t ]*)(?:" + Pattern.quote(key) + "|\"" + Pattern.quote(key) + "\"|'"+Pattern.quote(key)+"')\\s*=([^\\r\\n]*)(\\r?\\n|$)");
        StringBuilder output=new StringBuilder();String multiline=null;boolean found=false;
        for(String line:section.split("(?<=\\n)",-1)){
            Matcher m=p.matcher(line);
            if(multiline==null&&!found&&m.find()){
                if(m.group(2).contains("\"\"\"")||m.group(2).contains("'''"))throw new IOException("CONFIG_FORMAT: multiline managed values require manual editing");
                output.append(m.group(1)).append(key).append(" = ").append(value).append('\n');found=true;
            }else output.append(line);
            multiline=multilineState(line,multiline);
        }
        if(found)return output.toString();
        return section + (section.endsWith("\n") ? "" : "\n") + key + " = " + value + "\n";
    }

    private static String quote(String value) {
        return Json.GSON.toJson(value);
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
