package dev.ideamcp;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.Messages;

import java.nio.file.Path;

public final class AutoConfigureClientsAction extends DumbAwareAction {
    @Override
    public void actionPerformed(AnActionEvent event) {
        var project = event.getProject();
        String roaming = System.getenv("APPDATA");
        Path home = Path.of(System.getProperty("user.home"));
        var targets = ClientConfiguration.detect(home, roaming == null ? home.resolve(".config") : Path.of(roaming), project == null || project.getBasePath() == null ? null : Path.of(project.getBasePath()));
        if (targets.isEmpty()) {
            Messages.showInfoMessage(project, "Open your MCP client once, then run this action again.", "Java MCP");
            return;
        }
        String[] labels = targets.stream().map(t -> t.name() + " — " + t.file()).toArray(String[]::new);
        int chosen = Messages.showChooseDialog(project, "Select the client configuration to maintain. Reload the client after the first setup.", "Java MCP Auto-Configure Clients", null, labels, labels[0]);
        if (chosen < 0) return;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String message;
            try {
                var service = IdeaMcpService.getInstance();
                service.start();
                if (!service.isTokenPersistent())
                    throw new java.io.IOException("Enable persistent IDEA password storage before configuring clients");
                AutoClientService.getInstance().register(targets.get(chosen), service.connectionSnapshot());
                message = "Configured " + targets.get(chosen).name() + ". Reload the client to discover tools. This entry will be maintained when the IDE endpoint or token changes.";
            } catch (Exception e) {
                message = "Client configuration was not updated: " + (e instanceof java.io.IOException ? e.getMessage() : e.getClass().getSimpleName());
            }
            String result = message;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!ApplicationManager.getApplication().isDisposed() && (project == null || !project.isDisposed()))
                    Messages.showInfoMessage(project, result, "Java MCP");
            });
        });
    }
}
