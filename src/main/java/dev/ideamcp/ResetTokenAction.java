package dev.ideamcp;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

public final class ResetTokenAction extends DumbAwareAction {
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        if (Messages.showYesNoDialog(event.getProject(), "Generate a new saved MCP token?\n\nThe current token will stop working. Update your MCP clients with the new configuration after reset.",
                "Reset Java MCP Token", Messages.getWarningIcon()) == Messages.YES)
            ConnectionAction.copyConfiguration(event.getProject(), null, true);
    }
}
