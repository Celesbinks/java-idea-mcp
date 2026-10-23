package dev.ideamcp;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import java.awt.datatransfer.StringSelection;
import org.jetbrains.annotations.NotNull;

public final class ConnectionAction extends DumbAwareAction {
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        copyConfiguration(event.getProject(), null, false);
    }
    static void copyConfiguration(Project project, String fixedToken, boolean reset) {
        // PasswordSafe access can block; never hold up the UI or its event loop.
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (ApplicationManager.getApplication().isDisposed()) return;
            try {
                IdeaMcpService service = IdeaMcpService.getInstance();
                if (reset) service.resetToken();
                else if (fixedToken != null) service.setToken(fixedToken);
                String configuration = service.configuration();
                String endpoint = service.url();
                String persistence = service.isTokenPersistent()
                        ? "The token is stored in IDEA's credential store and reused after restart."
                        : "IDEA password storage is set to memory only. Enable password storage in Settings > Appearance & Behavior > System Settings > Passwords to keep the token after restart.";
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (ApplicationManager.getApplication().isDisposed() || (project != null && project.isDisposed())) return;
                    CopyPasteManager.getInstance().setContents(new StringSelection(configuration));
                    Messages.showInfoMessage(project, "MCP connection configuration copied to clipboard.\n\nEndpoint: " + endpoint
                            + "\nTransport: Streamable HTTP\nAuthentication: Bearer token (included in copied configuration)"
                            + "\n\n" + persistence
                            + (reset ? "\n\nToken reset. Update your MCP clients with the copied configuration." : ""), "Java MCP Connection");
                });
            } catch (Exception e) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!ApplicationManager.getApplication().isDisposed() && (project == null || !project.isDisposed()))
                        Messages.showErrorDialog(project, "Unable to configure Java MCP: " + e.getMessage(), "Java MCP");
                });
            }
        });
    }
}
