package dev.ideamcp;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.*;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

public final class IdeaMcpStartup implements StartupActivity, DumbAware {
    @Override public void runActivity(@NotNull Project project) {
        if (ApplicationManager.getApplication().isUnitTestMode()) return;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (project.isDisposed() || ApplicationManager.getApplication().isDisposed()) return;
            try { IdeaMcpService.getInstance().autoStart(); }
            catch (Exception e) { Logger.getInstance(IdeaMcpStartup.class).warn("Unable to start local Java MCP server", e); }
        });
    }
}
