package dev.ideamcp;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.CredentialStore;
import com.intellij.ide.passwordSafe.PasswordSafe;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Persistent, per-IDE credential. All production calls run on a background thread. */
final class TokenStore {
    private static final CredentialAttributes ATTRIBUTES = new CredentialAttributes(
            CredentialAttributesKt.generateServiceName("Java Symbol MCP", "Bearer token"));
    private final Supplier<CredentialStore> credentials;
    private final BooleanSupplier memoryOnly;

    TokenStore() { this(PasswordSafe::getInstance, () -> PasswordSafe.getInstance().isMemoryOnly()); }
    TokenStore(Supplier<CredentialStore> credentials, BooleanSupplier memoryOnly) {
        this.credentials = credentials;
        this.memoryOnly = memoryOnly;
    }
    String loadOrCreate() throws IOException {
        String saved = credentials.get().getPassword(ATTRIBUTES);
        if (saved != null) {
            try { validate(saved); }
            catch (IllegalArgumentException e) { throw new IOException("Stored MCP token is invalid. Use Set Fixed Token or Reset Token to replace it."); }
            return saved;
        }
        return save(generate());
    }
    String reset() throws IOException { return save(generate()); }
    String save(String token) throws IOException {
        validate(token);
        CredentialStore store = credentials.get();
        store.setPassword(ATTRIBUTES, token);
        if (!token.equals(store.getPassword(ATTRIBUTES))) throw new IOException("IDEA could not save the MCP token to its credential store.");
        return token;
    }
    boolean isPersistent() { return !memoryOnly.getAsBoolean(); }
    static void validate(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{32,256}"))
            throw new IllegalArgumentException("Token must contain 32-256 letters, digits, underscores or hyphens, without the Bearer prefix.");
    }
    private static String generate() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }
}
