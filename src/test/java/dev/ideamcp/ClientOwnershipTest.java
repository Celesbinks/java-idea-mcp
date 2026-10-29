package dev.ideamcp;

import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.Assert.*;

/** Temporary configurations only; no user credentials or IDE state are read. */
public class ClientOwnershipTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final ClientConfiguration.Connection FIRST = new ClientConfiguration.Connection(
            "http://127.0.0.1:64123/mcp", "fixture_owner_AAAAAAAAAAAAAAAAAAAAAAA");
    private static final ClientConfiguration.Connection SECOND = new ClientConfiguration.Connection(
            "http://127.0.0.1:64124/mcp", "fixture_owner_BBBBBBBBBBBBBBBBBBBBBBB");

    static String original() {
        return "[mcp_servers.idea-java]\nurl = '" + FIRST.url() + "'\n"
                + "http_headers = { Authorization = 'Bearer " + FIRST.token() + "', Extra = 'keep' }\n";
    }
    static String legacy(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
    static String rotated(String text) {
        return text.replace("'" + FIRST.url() + "'", "\"" + SECOND.url() + "\"")
                .replace("'Bearer " + FIRST.token() + "'", "\"Bearer " + SECOND.token() + "\"");
    }
    private ClientConfiguration.Target target(String text) throws Exception {
        Path path = temporary.newFile().toPath();
        Files.writeString(path, text);
        return new ClientConfiguration.Target("fixture", path, ClientConfiguration.Format.CODEX_TOML);
    }

    @Test public void clientPoliciesAndCommentsDoNotInvalidateBackgroundOwnership() throws Exception {
        var target = target(original());
        var owned = ClientConfiguration.update(target, FIRST, null);
        assertTrue(owned.digest().startsWith("codex-v2:"));
        String edited = original().replace("[mcp_servers.idea-java]\n", "[mcp_servers.idea-java] # client edit\n")
                + "# keep policy\nstartup_timeout_sec = 30\ntool_timeout_sec = 120\nenabled = false\n"
                + "enabled_tools = ['read_symbols']\n[mcp_servers.idea-java.tools.read_symbols]\napproval_mode = 'prompt'\n"
                + "[mcp_servers.other]\ncommand = 'keep'\n";
        Files.writeString(target.file(), edited);
        var result = ClientConfiguration.update(target, SECOND, owned.digest());
        assertEquals(rotated(edited), Files.readString(target.file()));
        assertFalse(ClientConfiguration.update(target, SECOND, result.digest()).changed());
    }

    @Test public void representationChangesKeepTheSameOwnershipAndOriginalText() throws Exception {
        var target = target(original());
        var owned = ClientConfiguration.update(target, FIRST, null);
        String expanded = "[mcp_servers.'idea-java']\r\nurl = \"" + FIRST.url() + "\"\r\n"
                + "[mcp_servers.'idea-java'.http_headers]\r\nExtra = \"keep\" # reordered\r\nAuthorization = \"Bearer " + FIRST.token() + "\"\r\n";
        Files.writeString(target.file(), expanded);
        var unchanged = ClientConfiguration.update(target, FIRST, owned.digest());
        assertEquals(owned.digest(), unchanged.digest());
        assertFalse(unchanged.changed());
        assertEquals(expanded, Files.readString(target.file()));
        String output = ClientConfiguration.update(target, SECOND, unchanged.digest()).digest();
        assertTrue(output.startsWith("codex-v2:"));
        assertEquals(expanded.replace(FIRST.url(), SECOND.url()).replace(FIRST.token(), SECOND.token()), Files.readString(target.file()));
    }

    @Test public void unchangedLegacyDigestMigratesWithoutRewritingTheFile() throws Exception {
        var target = target(original());
        var result = ClientConfiguration.update(target, FIRST, legacy(original()));
        assertFalse(result.changed());
        assertTrue(result.digest().startsWith("codex-v2:"));
        assertEquals(original(), Files.readString(target.file()));
    }

    @Test public void legacyRotationIsAllowedOnlyWhenOldRawOwnershipStillMatches() throws Exception {
        var target = target(original());
        var result = ClientConfiguration.update(target, SECOND, legacy(original()));
        assertTrue(result.changed());
        assertTrue(result.digest().startsWith("codex-v2:"));
        assertEquals(rotated(original()), Files.readString(target.file()));
    }

    @Test public void explicitRegistrationReadoptsMatchingLegacyDriftWithoutWriting() throws Exception {
        String edited = original() + "startup_timeout_sec = 30\n";
        var target = target(edited);
        assertThrows(IOException.class, () -> ClientConfiguration.update(target, FIRST, legacy(original())));
        var adopted = ClientConfiguration.register(target, FIRST, legacy(original()));
        assertFalse(adopted.changed());
        assertTrue(adopted.digest().startsWith("codex-v2:"));
        assertEquals(edited, Files.readString(target.file()));
    }

    @Test public void explicitReadoptionRejectsAConnectionThatDoesNotMatch() throws Exception {
        var target = target(original() + "enabled = false\n");
        String before = Files.readString(target.file());
        assertThrows(IOException.class, () -> ClientConfiguration.register(target, SECOND, legacy(original())));
        assertEquals(before, Files.readString(target.file()));
    }

    @Test public void editsToUrlAuthenticationHeadersAndTransportRemainProtected() throws Exception {
        var target = target(original());
        var owned = ClientConfiguration.update(target, FIRST, null);
        for (String changed : new String[]{original().replace("64123", "64125"),
                original().replace(FIRST.token(), "fixture_foreign_CCCCCCCCCCCCCCCCCCCCCC"),
                original().replace("Extra = 'keep'", "Extra = 'manual'"),
                original() + "command = 'foreign'\n", original() + "bearer_token_env_var = 'OTHER'\n",
                original() + "env_http_headers = { Authorization = 'OTHER' }\n",
                original() + "http_headers_helper = ['foreign']\n"}) {
            Files.writeString(target.file(), changed);
            assertThrows(IOException.class, () -> ClientConfiguration.update(target, SECOND, owned.digest()));
            assertEquals(changed, Files.readString(target.file()));
        }
    }

    @Test public void explicitReadoptionStillRejectsIncompatibleAuthentication() throws Exception {
        for (String incompatible : new String[]{"command = 'foreign'", "bearer_token_env_var = 'OTHER'",
                "env_http_headers = { Authorization = 'OTHER' }", "http_headers_helper = ['foreign']"}) {
            var target = target(original() + incompatible + "\n");
            String before = Files.readString(target.file());
            assertThrows(IOException.class, () -> ClientConfiguration.register(target, FIRST, legacy(original())));
            assertEquals(before, Files.readString(target.file()));
        }
    }

    @Test public void deletionOrMalformedEntriesAreNeverRecreatedFromStaleOwnership() throws Exception {
        var target = target(original());
        var owned = ClientConfiguration.update(target, FIRST, null);
        for (String changed : new String[]{"[mcp_servers.other]\ncommand = 'keep'\n", "[broken\n"}) {
            Files.writeString(target.file(), changed);
            assertThrows(IOException.class, () -> ClientConfiguration.register(target, FIRST, owned.digest()));
            assertEquals(changed, Files.readString(target.file()));
        }
    }
}
