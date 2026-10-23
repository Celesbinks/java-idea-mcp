package dev.ideamcp;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

public final class StopAction extends DumbAwareAction {
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            IdeaMcpService.getInstance().stop();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!ApplicationManager.getApplication().isDisposed() && (project == null || !project.isDisposed()))
                    Messages.showInfoMessage(project, "Java MCP server stopped. The saved token is kept. Use Copy Connection Configuration to restart it.", "Java MCP");
            });
        });
    }
}
