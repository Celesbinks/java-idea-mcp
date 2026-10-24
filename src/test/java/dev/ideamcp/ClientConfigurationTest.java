package dev.ideamcp;

import com.google.gson.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.nio.file.*;
import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;

public class ClientConfigurationTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();
    private static final ClientConfiguration.Connection FIRST = new ClientConfiguration.Connection("http://127.0.0.1:64123/mcp", "fixture_token_AAAAAAAAAAAAAAAAAAAAAA", new ClientConfiguration.Launcher("fixture-java", List.of("-cp", "fixture-cp", "dev.ideamcp.protocol.StdioBridge")));
    private static final ClientConfiguration.Connection SECOND = new ClientConfiguration.Connection("http://127.0.0.1:64124/mcp", "fixture_token_BBBBBBBBBBBBBBBBBBBBBB", FIRST.launcher());

    @Test
    public void allClientFormatsAreIdempotentAndRotateManagedEntries() throws Exception {
        for (var format : ClientConfiguration.Format.values()) {
            Path path = temporary.getRoot().toPath().resolve(format.name());
            var target = new ClientConfiguration.Target("fixture", path, format);
            var first = ClientConfiguration.update(target, FIRST, null);
            assertTrue(first.changed());
            assertFalse(ClientConfiguration.update(target, FIRST, first.digest()).changed());
            var second = ClientConfiguration.update(target, SECOND, first.digest());
            assertTrue(second.changed());
            String content = Files.readString(path);
            assertTrue(content.contains(SECOND.url()));
            assertFalse(content.contains(FIRST.token()));
            assertFalse(ClientConfiguration.update(target, SECOND, second.digest()).changed());
            if (format == ClientConfiguration.Format.WINDSURF_JSON) assertTrue(content.contains("serverUrl"));
            if (format == ClientConfiguration.Format.STDIO_JSON) {
                assertTrue(content.contains("IDEA_JAVA_MCP_TOKEN"));
                assertFalse(content.contains("Authorization"));
            }
        }
    }

    @Test
    public void unrelatedSettingsAndTomlMultilineSectionsSurvive() throws Exception {
        String toml = "model = \"fixture\"\nnotes = '''\n[mcp_servers.idea-java]\nurl = \"inside-string\"\n'''\n[mcp_servers.other]\ncommand = \"fixture\"\n";
        String merged = ClientConfiguration.merge(ClientConfiguration.Format.CODEX_TOML, toml, FIRST);
        assertTrue(merged.startsWith(toml));
        assertFalse(org.tomlj.Toml.parse(merged).hasErrors());
        String json = ClientConfiguration.merge(ClientConfiguration.Format.MCP_JSON, "{\"custom\":7,\"mcpServers\":{\"other\":{\"command\":\"fixture\"}}}", FIRST);
        var root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(7, root.get("custom").getAsInt());
        assertEquals("fixture", root.getAsJsonObject("mcpServers").getAsJsonObject("other").get("command").getAsString());
        String flat = "['mcp_servers.idea-java']\nurl = 'unrelated'\n";
        String result = ClientConfiguration.merge(ClientConfiguration.Format.CODEX_TOML, flat, FIRST);
        assertTrue(result.startsWith(flat));
        String array = "[mcp_servers.idea-java]\nenabled = true\n[[unrelated]]\nname = 'keep'\n";
        result = ClientConfiguration.merge(ClientConfiguration.Format.CODEX_TOML, array, FIRST);
        assertEquals(FIRST.url(), org.tomlj.Toml.parse(result).getString("mcp_servers.idea-java.url"));
        assertTrue(result.contains("name = 'keep'"));
        String literal="[mcp_servers.idea-java]\nnotes = '''\nurl = 'literal text'\n'''\nurl = 'old'\n";
        result=ClientConfiguration.merge(ClientConfiguration.Format.CODEX_TOML,literal,FIRST);assertTrue(result.contains("url = 'literal text'"));assertEquals(FIRST.url(),org.tomlj.Toml.parse(result).getString("mcp_servers.idea-java.url"));
        assertThrows(IOException.class, () -> ClientConfiguration.merge(ClientConfiguration.Format.MCP_JSON, "{\"mcpServers\":{},\"mcpServers\":{}}", FIRST));
    }

    @Test
    public void userEditsOtherOwnersAndMalformedFilesAreNeverOverwritten() throws Exception {
        Path path = temporary.newFile().toPath();
        var target = new ClientConfiguration.Target("fixture", path, ClientConfiguration.Format.MCP_JSON);
        var first = ClientConfiguration.update(target, FIRST, null);
        assertThrows(IOException.class, () -> ClientConfiguration.update(target, SECOND, null));
        Files.writeString(path, Files.readString(path).replace("64123", "64125"));
        String edited = Files.readString(path);
        assertThrows(IOException.class, () -> ClientConfiguration.update(target, SECOND, first.digest()));
        assertEquals(edited, Files.readString(path));
        Files.writeString(path, "{broken");
        assertThrows(IOException.class, () -> ClientConfiguration.update(target, FIRST, null));
        assertEquals("{broken", Files.readString(path));
        assertThrows(IOException.class, () -> ClientConfiguration.merge(ClientConfiguration.Format.CODEX_TOML, "[broken", FIRST));
    }

    @Test
    public void manualCodexHeaderTableIsAdoptedWithoutChangingUnknownKeys() throws Exception {
        String input = "model = \"fixture\"\n[mcp_servers.idea-java]\nurl = \"" + FIRST.url() + "\"\nenabled = true\n[mcp_servers.idea-java.http_headers]\nAuthorization = \"Bearer " + FIRST.token() + "\"\nExtra = \"keep\"\n";
        Path file = temporary.newFile().toPath();
        Files.writeString(file, input);
        var t = new ClientConfiguration.Target("Codex", file, ClientConfiguration.Format.CODEX_TOML);
        var adopted = ClientConfiguration.update(t, FIRST, null);
        ClientConfiguration.update(t, SECOND, adopted.digest());
        String output = Files.readString(file);
        assertTrue(output.contains("enabled = true"));
        assertTrue(output.contains("Extra = \"keep\""));
    }

    @Test
    public void detectionUsesOnlyTemporaryClientLocations() throws Exception {
        Path home = temporary.newFolder("home").toPath(), roaming = temporary.newFolder("roaming").toPath();
        Files.createDirectories(home.resolve(".codex"));
        Files.createDirectories(roaming.resolve("Claude"));
        var targets = ClientConfiguration.detect(home, roaming, null);
        assertEquals(2, targets.size());
        assertEquals(ClientConfiguration.Format.STDIO_JSON, targets.get(1).format());
        assertEquals("Connection[redacted]", FIRST.toString());
        assertThrows(IllegalArgumentException.class, () -> new ClientConfiguration.Connection("https://example.org/mcp", FIRST.token()));
    }
}
