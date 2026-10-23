package dev.ideamcp;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

public final class SetTokenAction extends DumbAwareAction {
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        String value = Messages.showPasswordDialog(event.getProject(), "Paste a fixed token without the Bearer prefix (32-256 letters, digits, underscores or hyphens).\n\nTo keep an existing client configuration, paste the same token used by that client.\nSaving replaces the currently active token and stores it for future restarts.",
                "Set Fixed Java MCP Token", Messages.getQuestionIcon());
        if (value != null) ConnectionAction.copyConfiguration(event.getProject(), value.trim(), false);
    }
}
