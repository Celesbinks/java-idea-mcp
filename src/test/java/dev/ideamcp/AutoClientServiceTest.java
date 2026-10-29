package dev.ideamcp;

import com.intellij.testFramework.LightPlatformTestCase;

import java.nio.file.*;

public final class AutoClientServiceTest extends LightPlatformTestCase {
    public void testExplicitRegistrationRecoversLegacyConflictAndRefreshPreservesClientPolicy() throws Exception {
        Path directory = Files.createTempDirectory("mcp-ownership-fixture-");
        try {
            Path file = directory.resolve("config.toml");
            String original = ClientOwnershipTest.original();
            String edited = original + "tool_timeout_sec = 120\nenabled = false\n";
            Files.writeString(file, edited);
            var first = new ClientConfiguration.Connection("http://127.0.0.1:64123/mcp", "fixture_owner_AAAAAAAAAAAAAAAAAAAAAAA");
            var second = new ClientConfiguration.Connection("http://127.0.0.1:64124/mcp", "fixture_owner_BBBBBBBBBBBBBBBBBBBBBBB");
            var registration = new AutoClientService.Registration();
            registration.name = "fixture"; registration.path = file.toAbsolutePath().toString();
            registration.format = "CODEX_TOML"; registration.digest = ClientOwnershipTest.legacy(original);
            var state = new AutoClientService.Settings(); state.clients.add(registration);
            var service = new AutoClientService(); service.loadState(state);
            service.refresh(first);
            assertEquals("CONFIG_CONFLICT", service.getState().clients.getFirst().lastError);
            assertEquals(edited, Files.readString(file));
            service.register(new ClientConfiguration.Target("fixture", file, ClientConfiguration.Format.CODEX_TOML), first);
            assertEquals(edited, Files.readString(file));
            assertEquals(1, service.getState().clients.size());
            assertEquals("", service.getState().clients.getFirst().lastError);
            assertTrue(service.getState().clients.getFirst().digest.startsWith("codex-v2:"));
            var reloaded = new AutoClientService(); reloaded.loadState(service.getState());
            String reformatted = "# another policy edit\n" + edited.replace("120", "180");
            Files.writeString(file, reformatted); reloaded.refresh(second);
            assertEquals(ClientOwnershipTest.rotated(reformatted), Files.readString(file));
            assertEquals("", reloaded.getState().clients.getFirst().lastError);
            String serialized = com.intellij.openapi.util.JDOMUtil.write(com.intellij.util.xmlb.XmlSerializer.serialize(reloaded.getState()));
            assertFalse(serialized.contains(first.token())); assertFalse(serialized.contains(second.token()));
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    public void testInlineHeadersAreAdoptedAndRefreshedWithOtherHeadersIntact() throws Exception {
        Path directory = Files.createTempDirectory("mcp-inline-managed-fixture-");
        try {
            Path file = directory.resolve("config.toml");
            var first = new ClientConfiguration.Connection("http://127.0.0.1:64123/mcp", "fixture_token_AAAAAAAAAAAAAAAAAAAAAA");
            var second = new ClientConfiguration.Connection("http://127.0.0.1:64124/mcp", "fixture_token_BBBBBBBBBBBBBBBBBBBBBB");
            String input = "# keep\n[mcp_servers.idea-java]\nurl = \"" + first.url() + "\"\n"
                    + "http_headers = { Authorization = \"Bearer " + first.token() + "\", Extra = '😀,={}' } # keep\n";
            Files.writeString(file, input);
            var service = new AutoClientService();
            service.register(new ClientConfiguration.Target("fixture", file, ClientConfiguration.Format.CODEX_TOML), first);
            assertEquals(input, Files.readString(file));
            var reloaded = new AutoClientService();
            reloaded.loadState(service.getState());
            reloaded.refresh(second);
            assertEquals(input.replace(first.url(), second.url()).replace(first.token(), second.token()), Files.readString(file));
            assertEquals("", reloaded.getState().clients.getFirst().lastError);
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    public void testReloadedOwnershipRotatesOnlyManagedEntriesAndStopsOnConflict() throws Exception {
        Path directory = Files.createTempDirectory("mcp-managed-fixture-");
        try {
            Path file = directory.resolve("config.toml");
            var target = new ClientConfiguration.Target("fixture", file, ClientConfiguration.Format.CODEX_TOML);
            var first = new ClientConfiguration.Connection("http://127.0.0.1:64123/mcp", "fixture_token_AAAAAAAAAAAAAAAAAAAAAA");
            var second = new ClientConfiguration.Connection("http://127.0.0.1:64124/mcp", "fixture_token_BBBBBBBBBBBBBBBBBBBBBB");
            AutoClientService service = new AutoClientService();
            service.register(target, first);
            var saved = service.getState();
            String xml = com.intellij.openapi.util.JDOMUtil.write(com.intellij.util.xmlb.XmlSerializer.serialize(saved));
            assertFalse(xml.contains(first.token()));
            AutoClientService reloaded = new AutoClientService();
            reloaded.loadState(saved);
            reloaded.refresh(second);
            assertTrue(Files.readString(file).contains(second.url()));
            String edited = Files.readString(file).replace("64124", "64125");
            Files.writeString(file, edited);
            reloaded.refresh(first);
            assertEquals(edited, Files.readString(file));
            assertEquals("CONFIG_CONFLICT", reloaded.getState().clients.getFirst().lastError);
            reloaded.forget(file.toString());
            assertTrue(reloaded.getState().clients.isEmpty());
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
