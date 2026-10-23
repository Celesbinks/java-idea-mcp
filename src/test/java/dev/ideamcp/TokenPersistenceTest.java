package dev.ideamcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.credentialStore.*;
import org.junit.Test;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public final class TokenPersistenceTest {
    @Test public void tokenSurvivesServiceRecreationAndStopStart() throws Exception {
        TestCredentials credentials = new TestCredentials();
        String first;
        IdeaMcpService previous = service(credentials);
        try {
            first = token(previous);
            assertEquals(43, first.length());
            previous.stop();
            assertEquals(first, token(previous));
            assertEquals(1, credentials.writes);
        } finally { previous.dispose(); }
        // New service and new TokenStore instance, sharing only the credential provider.
        IdeaMcpService restarted = service(credentials);
        try {
            assertEquals(first, token(restarted));
            assertEquals(1, credentials.writes);
            assertEquals(200, ping(restarted.url(), first));
        } finally { restarted.dispose(); }
    }
    @Test public void resetInvalidatesOldTokenAndPersistsReplacement() throws Exception {
        TestCredentials credentials = new TestCredentials();
        IdeaMcpService service = service(credentials);
        String replacement;
        try {
            String previous = token(service);
            service.resetToken();
            replacement = token(service);
            assertNotEquals(previous, replacement);
            assertEquals(401, ping(service.url(), previous));
            assertEquals(200, ping(service.url(), replacement));
        } finally { service.dispose(); }
        IdeaMcpService restarted = service(credentials);
        try { assertEquals(replacement, token(restarted)); }
        finally { restarted.dispose(); }
    }
    @Test public void customTokenKeepsExistingClientCredential() throws Exception {
        TestCredentials credentials = new TestCredentials();
        IdeaMcpService service = service(credentials);
        String fixed = "existing-client-token-012345678901234567890";
        try {
            service.setToken(fixed);
            assertEquals(fixed, token(service));
            assertEquals(200, ping(service.url(), fixed));
        } finally { service.dispose(); }
        IdeaMcpService restarted = service(credentials);
        try { assertEquals(fixed, token(restarted)); }
        finally { restarted.dispose(); }
    }
    @Test public void failureToSaveDoesNotStartAnEphemeralServer() throws Exception {
        TestCredentials credentials = new TestCredentials();
        credentials.rejectWrites = true;
        IdeaMcpService service = service(credentials);
        try {
            assertThrows(IOException.class, service::start);
            assertNull(service.url());
        } finally { service.dispose(); }
    }
    @Test public void failedResetKeepsCurrentServerAndToken() throws Exception {
        TestCredentials credentials = new TestCredentials();
        IdeaMcpService service = service(credentials);
        try {
            String original = token(service);
            String endpoint = service.url();
            credentials.rejectWrites = true;
            assertThrows(IOException.class, service::resetToken);
            assertEquals(original, token(service));
            assertEquals(endpoint, service.url());
            assertEquals(200, ping(endpoint, original));
        } finally { service.dispose(); }
    }
    @Test public void invalidCustomTokenDoesNotChangeStoredCredential() throws Exception {
        TestCredentials credentials = new TestCredentials();
        IdeaMcpService service = service(credentials);
        try {
            String original = token(service);
            assertThrows(IllegalArgumentException.class, () -> service.setToken("too-short"));
            assertThrows(IllegalArgumentException.class, () -> service.setToken(original + "\r\nInjected: header"));
            assertEquals(original, token(service));
            assertEquals(1, credentials.writes);
        } finally { service.dispose(); }
    }
    @Test public void malformedStoredCredentialIsNotSilentlyRegenerated() throws Exception {
        TestCredentials credentials = new TestCredentials();
        TokenStore store = new TokenStore(() -> credentials, () -> false);
        store.loadOrCreate();
        credentials.entries.replaceAll((key, value) -> new Credentials(null, "broken"));
        assertThrows(IOException.class, store::loadOrCreate);
        assertEquals(1, credentials.writes);
    }
    @Test public void reportsMemoryOnlyPasswordStorage() {
        TestCredentials credentials = new TestCredentials();
        assertFalse(new TokenStore(() -> credentials, () -> true).isPersistent());
        assertTrue(new TokenStore(() -> credentials, () -> false).isPersistent());
    }
    @Test public void disposalDoesNotWaitForPasswordStorageAndCancelsStartup() throws Exception {
        TestCredentials credentials = new TestCredentials();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        TokenStore store = new TokenStore(() -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test credential wait timed out"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            return credentials;
        }, () -> false);
        IdeaMcpService service = new IdeaMcpService(store, 0);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> startup = executor.submit(() -> { service.start(); return null; });
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                executor.submit(service::dispose).get(1, TimeUnit.SECONDS);
            } finally { release.countDown(); }
            ExecutionException error = assertThrows(ExecutionException.class, () -> startup.get(3, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof IOException);
            assertNull(service.url());
        } finally { release.countDown(); service.dispose(); }
    }
    private static IdeaMcpService service(TestCredentials credentials) {
        return new IdeaMcpService(new TokenStore(() -> credentials, () -> false), 0);
    }
    private static String token(IdeaMcpService service) throws Exception {
        JsonObject config = JsonParser.parseString(service.configuration()).getAsJsonObject();
        return config.getAsJsonObject("mcpServers").getAsJsonObject("idea-java").getAsJsonObject("headers")
                .get("Authorization").getAsString().substring("Bearer ".length());
    }
    private static int ping(String endpoint, String token) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }
    private static final class TestCredentials implements CredentialStore {
        final Map<CredentialAttributes, Credentials> entries = new HashMap<>();
        int writes;
        boolean rejectWrites;
        @Override public Credentials get(CredentialAttributes attributes) { return entries.get(attributes); }
        @Override public void set(CredentialAttributes attributes, Credentials value) {
            if (!rejectWrites) { entries.put(attributes, value); writes++; }
        }
    }
}
