package dev.ideamcp;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** Real TOML parsing and file updates; never reads a user's client configuration. */
public class CodexInlineHeadersTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final ClientConfiguration.Connection FIRST = new ClientConfiguration.Connection(
            "http://127.0.0.1:64123/mcp", "fixture_inline_AAAAAAAAAAAAAAAAAAAAAA");
    private static final ClientConfiguration.Connection SECOND = new ClientConfiguration.Connection(
            "http://127.0.0.1:64124/mcp", "fixture_inline_BBBBBBBBBBBBBBBBBBBBBB");

    @Test public void adoptsAndRotatesWithoutChangingOtherSettingsHeadersOrComments() throws Exception {
        for (String newline : new String[]{"\n", "\r\n"}) {
            String input = ("# keep global settings\nmodel = 'fixture'\n"
                    + "[mcp_servers.idea-java]\nurl = '" + FIRST.url() + "' # keep URL comment\n"
                    + "enabled = true\ntool_timeout_sec = 37\n"
                    + "http_headers = { 'X.Meta' = '😀 {keep}, # = \"quoted\"', Authorization = \"Bearer "
                    + FIRST.token() + "\", Extra = 'preserve' } # keep headers comment\n"
                    + "[mcp_servers.idea-java.tools.read_symbols]\napproval_mode = 'prompt'\n"
                    + "[mcp_servers.other]\ncommand = 'preserve'\n").replace("\n", newline);
            Path file = temporary.newFile().toPath();
            Files.writeString(file, input);
            var target = new ClientConfiguration.Target("fixture", file, ClientConfiguration.Format.CODEX_TOML);
            var adopted = ClientConfiguration.update(target, FIRST, null);
            assertFalse(adopted.changed());
            assertEquals(input, Files.readString(file));
            // Changes outside this server's owned sections must survive a background refresh.
            String edited = "# unrelated edit" + newline + input;
            Files.writeString(file, edited);
            var rotated = ClientConfiguration.update(target, SECOND, adopted.digest());
            String expected = edited.replace("'" + FIRST.url() + "'", "\"" + SECOND.url() + "\"")
                    .replace("Bearer " + FIRST.token(), "Bearer " + SECOND.token());
            assertEquals(expected, Files.readString(file));
            assertFalse(ClientConfiguration.update(target, SECOND, rotated.digest()).changed());
            assertEquals("😀 {keep}, # = \"quoted\"", parsed(expected).getString("mcp_servers.idea-java.http_headers.\"X.Meta\""));
        }
    }

    @Test public void handlesQuotedEscapedKeysAndLiteralValuesAfterUnicode() throws Exception {
        for (String key : new String[]{"http_headers", "'http_headers'", "\"http_headers\"", "\"http\\u005fheaders\""}) {
            for (String authorization : new String[]{"'Authorization'", "\"Authoriz\\u0061tion\""}) {
                String other = "'X.Equal' = '😀 a=b, {c} # unchanged'";
                String input = "[mcp_servers.\"idea-java\"]\nurl = '" + FIRST.url() + "'\n"
                        + key + " = { " + other + ", " + authorization + " = 'Bearer " + FIRST.token() + "' } # keep\n";
                String output = ClientConfiguration.merge(ClientConfiguration.Format.CODEX_TOML, input, SECOND);
                assertEquals("Bearer " + SECOND.token(), parsed(output).getString("mcp_servers.idea-java.http_headers.Authorization"));
                assertEquals("😀 a=b, {c} # unchanged", parsed(output).getString("mcp_servers.idea-java.http_headers.\"X.Equal\""));
                assertTrue(output.contains(key + " = { " + other + ", " + authorization));
                assertTrue(output.endsWith(" } # keep\n"));
                assertEquals(output, ClientConfiguration.merge(ClientConfiguration.Format.CODEX_TOML, output, SECOND));
            }
        }
    }

    @Test public void insertsMissingAuthorizationIntoEmptyOrPopulatedInlineTable() throws Exception {
        for (String headers : new String[]{"{}", "{ 'X.Custom' = 'keep,={}' }"}) {
            String input = "[mcp_servers.idea-java]\nurl = 'old'\nhttp_headers = " + headers
                    + " # keep\nenabled = true\n[unrelated]\nanswer = 42\n";
            String output = ClientConfiguration.merge(ClientConfiguration.Format.CODEX_TOML, input, FIRST);
            var parsed = parsed(output);
            assertEquals(FIRST.url(), parsed.getString("mcp_servers.idea-java.url"));
            assertEquals("Bearer " + FIRST.token(), parsed.getString("mcp_servers.idea-java.http_headers.Authorization"));
            assertTrue(parsed.getBoolean("mcp_servers.idea-java.enabled"));
            assertEquals(Long.valueOf(42), parsed.getLong("unrelated.answer"));
            if (headers.contains("X.Custom")) assertTrue(output.contains("'X.Custom' = 'keep,={}'"));
            assertFalse(output.contains("[mcp_servers.idea-java.http_headers]"));
            assertEquals(output, ClientConfiguration.merge(ClientConfiguration.Format.CODEX_TOML, output, FIRST));
        }
    }

    @Test public void multilineStringsAndHeaderLookingNotesArePreserved() throws Exception {
        String note = "notes = '''\nhttp_headers = { Authorization = \"not a header\" }\n'''\n";
        String custom = "Extra = \"\"\"line one\nline two { } , # =\"\"\"";
        String input = "[mcp_servers.idea-java]\n" + note + "url = '" + FIRST.url() + "'\n"
                + "http_headers = { " + custom + ", Authorization = \"\"\"Bearer " + FIRST.token() + "\"\"\" } # keep\n";
        parsed(input);
        String output = ClientConfiguration.merge(ClientConfiguration.Format.CODEX_TOML, input, SECOND);
        assertEquals("Bearer " + SECOND.token(), parsed(output).getString("mcp_servers.idea-java.http_headers.Authorization"));
        assertEquals(parsed(input).getString("mcp_servers.idea-java.http_headers.Extra"),
                parsed(output).getString("mcp_servers.idea-java.http_headers.Extra"));
        assertTrue(output.contains(note));
        assertTrue(output.contains(custom));
        String expanded = "[mcp_servers.idea-java]\n" + note + "url = 'old'\n"
                + "[mcp_servers.idea-java.http_headers] # note = {keep}\nAuthorization = 'old' # keep\nExtra = 'unchanged'\n";
        output = ClientConfiguration.merge(ClientConfiguration.Format.CODEX_TOML, expanded, FIRST);
        assertTrue(output.contains(note));
        assertTrue(output.contains("[mcp_servers.idea-java.http_headers] # note = {keep}\n"));
        assertTrue(output.contains(" # keep\nExtra = 'unchanged'\n"));
        assertEquals("Bearer " + FIRST.token(), parsed(output).getString("mcp_servers.idea-java.http_headers.Authorization"));
    }

    @Test public void foreignOwnersManualEditsAndMalformedInlineTablesRemainUntouched() throws Exception {
        Path file = temporary.newFile().toPath();
        String input = "[mcp_servers.idea-java]\nurl = \"" + FIRST.url() + "\"\n"
                + "http_headers = { Authorization = \"Bearer " + FIRST.token() + "\", Extra = 'keep' }\n";
        Files.writeString(file, input);
        var target = new ClientConfiguration.Target("fixture", file, ClientConfiguration.Format.CODEX_TOML);
        assertThrows(IOException.class, () -> ClientConfiguration.update(target, SECOND, null));
        assertEquals(input, Files.readString(file));
        var owned = ClientConfiguration.update(target, FIRST, null);
        String edited = input.replace("Extra = 'keep'", "Extra = 'user edit'");
        Files.writeString(file, edited);
        assertThrows(IOException.class, () -> ClientConfiguration.update(target, SECOND, owned.digest()));
        assertEquals(edited, Files.readString(file));
        String broken = input.replace("Extra = 'keep'", "Authorization = 'duplicate'");
        Files.writeString(file, broken);
        assertThrows(IOException.class, () -> ClientConfiguration.update(target, FIRST, null));
        assertEquals(broken, Files.readString(file));
    }

    @Test public void incompatibleAuthenticationAndInvalidHeaderTypesAreRejected() throws Exception {
        for (String setting : new String[]{"command = 'other'", "bearer_token_env_var = 'OTHER_TOKEN'",
                "env_http_headers = { Authorization = 'OTHER_TOKEN' }", "http_headers_helper = ['other']"}) {
            String input = "[mcp_servers.idea-java]\n" + setting + "\nhttp_headers = {}\n";
            assertThrows(IOException.class, () -> ClientConfiguration.merge(ClientConfiguration.Format.CODEX_TOML, input, FIRST));
        }
        for (String headers : new String[]{"false", "{ Authorization = 42 }"}) {
            String input = "[mcp_servers.idea-java]\nhttp_headers = " + headers + "\n";
            assertThrows(IOException.class, () -> ClientConfiguration.merge(ClientConfiguration.Format.CODEX_TOML, input, FIRST));
        }
    }

    private static TomlParseResult parsed(String input) {
        var parsed = Toml.parse(input);
        assertFalse(parsed.errors().toString(), parsed.hasErrors());
        return parsed;
    }
}
