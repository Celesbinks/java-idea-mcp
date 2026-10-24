package dev.ideamcp;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import dev.ideamcp.protocol.*;

import java.io.IOException;
import java.net.BindException;

import static dev.ideamcp.protocol.Json.object;

@Service(Service.Level.APP)
public final class IdeaMcpService implements Disposable {
    // Serialize background credential operations independently of lifecycle state.
    // dispose()/stop() must never wait for a PasswordSafe dialog on the UI thread.
    private final Object operations = new Object();
    private final TokenStore tokenStore;
    private final int preferredPort;
    private McpHttpServer server;
    private String token;
    // Stop applies to the entire IDE process; opening another project must not restart it.
    private boolean manuallyStopped;
    private boolean disposed;
    private long revision;

    public IdeaMcpService() {
        this(new TokenStore(), Integer.getInteger("idea.mcp.port", 64123));
    }

    IdeaMcpService(TokenStore tokenStore, int preferredPort) {
        this.tokenStore = tokenStore;
        this.preferredPort = preferredPort;
    }

    public static IdeaMcpService getInstance() {
        return ApplicationManager.getApplication().getService(IdeaMcpService.class);
    }

    public void autoStart() throws IOException {
        start(true);
    }

    public void start() throws IOException {
        start(false);
    }

    private void start(boolean automatic) throws IOException {
        synchronized (operations) {
            long startingRevision;
            synchronized (this) {
                if (disposed) throw new IOException("Java MCP service has been disposed");
                if (server != null || (automatic && manuallyStopped)) return;
                startingRevision = revision;
            }
            String candidateToken = tokenStore.loadOrCreate();
            synchronized (this) {
                if (disposed || revision != startingRevision) throw new IOException("Java MCP startup was cancelled");
                int port = preferredPort;
                if (port < 0 || port > 65535) throw new IOException("idea.mcp.port must be between 0 and 65535");
                McpHttpServer candidate;
                RpcHandler rpc = new RpcHandler(new IdeaToolBackend());
                try {
                    candidate = new McpHttpServer(port, candidateToken, rpc);
                } catch (BindException e) {
                    candidate = new McpHttpServer(0, candidateToken, rpc);
                }
                candidate.start();
                server = candidate;
                token = candidateToken;
                manuallyStopped = false;
            }
            if (isTokenPersistent() && !ApplicationManager.getApplication().isUnitTestMode()) {
                ClientConfiguration.Connection connection = connectionSnapshot();
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    synchronized (operations) {
                        synchronized (this) {
                            if (server == null || !server.url().equals(connection.url()) || !token.equals(connection.token()))
                                return;
                        }
                        AutoClientService.getInstance().refresh(connection);
                    }
                });
            }
        }
    }

    public String configuration() throws IOException {
        synchronized (operations) {
            start();
            synchronized (this) {
                if (server == null) throw new IOException("Java MCP server was stopped");
                return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(object("mcpServers", object("idea-java", object(
                        "type", "http", "url", server.url(), "headers", object("Authorization", "Bearer " + token)))));
            }
        }
    }

    public synchronized String url() {
        return server == null ? null : server.url();
    }

    synchronized ClientConfiguration.Connection connectionSnapshot() throws IOException {
        if (server == null) throw new IOException("Java MCP server was stopped");
        return AutoClientService.connection(server.url(), token);
    }

    public boolean isTokenPersistent() {
        return tokenStore.isPersistent();
    }

    public void resetToken() throws IOException {
        synchronized (operations) {
            synchronized (this) {
                if (disposed) throw new IOException("Java MCP service has been disposed");
            }
            tokenStore.reset();
            stop();
            start();
        }
    }

    public void setToken(String value) throws IOException {
        synchronized (operations) {
            synchronized (this) {
                if (disposed) throw new IOException("Java MCP service has been disposed");
            }
            tokenStore.save(value);
            stop();
            start();
        }
    }

    public synchronized void stop() {
        revision++;
        manuallyStopped = true;
        if (server != null) server.close();
        server = null;
        token = null;
    }

    @Override
    public synchronized void dispose() {
        disposed = true;
        stop();
    }
}
