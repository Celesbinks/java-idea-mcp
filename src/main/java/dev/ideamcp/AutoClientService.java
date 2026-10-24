package dev.ideamcp;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.PathUtil;
import dev.ideamcp.protocol.StdioBridge;

import java.nio.file.*;
import java.util.*;

/**
 * Stores ownership hashes, never tokens. Only explicit registrations are refreshed.
 */
@Service(Service.Level.APP)
@State(name = "JavaSymbolMcpClients", storages = @Storage("java-symbol-mcp-clients.xml"))
public final class AutoClientService implements PersistentStateComponent<AutoClientService.Settings> {
    public static final class Registration {
        public String name = "", path = "", format = "", digest = "", lastError = "";
    }

    public static final class Settings {
        public List<Registration> clients = new ArrayList<>();
    }

    private Settings state = new Settings();

    public static AutoClientService getInstance() {
        return ApplicationManager.getApplication().getService(AutoClientService.class);
    }

    @Override
    public synchronized Settings getState() {
        return com.intellij.util.xmlb.XmlSerializerUtil.createCopy(state);
    }

    @Override
    public synchronized void loadState(Settings value) {
        state = value;
    }

    static ClientConfiguration.Connection connection(String url, String token) {
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String cp = PathUtil.getJarPathForClass(StdioBridge.class) + java.io.File.pathSeparator + PathUtil.getJarPathForClass(com.google.gson.JsonObject.class);
        return new ClientConfiguration.Connection(url, token, new ClientConfiguration.Launcher(executable, List.of("-cp", cp, StdioBridge.class.getName())));
    }

    public synchronized void register(ClientConfiguration.Target target, ClientConfiguration.Connection connection) throws java.io.IOException {
        String path = target.file().toAbsolutePath().normalize().toString();
        Registration existing = state.clients.stream().filter(r -> r.path.equals(path)).findFirst().orElse(null);
        var update = ClientConfiguration.update(target, connection, existing == null ? null : existing.digest);
        Registration r = existing == null ? new Registration() : existing;
        r.name = target.name();
        r.path = path;
        r.format = target.format().name();
        r.digest = update.digest();
        r.lastError = "";
        if (existing == null) state.clients.add(r);
    }

    public synchronized void forget(String path) {
        state.clients.removeIf(r -> r.path.equals(path));
    }

    public synchronized void refresh(ClientConfiguration.Connection connection) {
        for (Registration r : state.clients) {
            try {
                var result = ClientConfiguration.update(new ClientConfiguration.Target(r.name, Path.of(r.path), ClientConfiguration.Format.valueOf(r.format)), connection, r.digest);
                r.digest = result.digest();
                r.lastError = "";
            } catch (Exception e) {
                r.lastError = e instanceof java.io.IOException ? e.getMessage().split(":", 2)[0] : "CONFIG_UPDATE_FAILED";
                Logger.getInstance(AutoClientService.class).warn("Java MCP client configuration needs attention: " + r.name + " (" + r.lastError + ")");
            }
        }
    }
}
