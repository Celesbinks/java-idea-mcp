package dev.ideamcp;

import com.intellij.testFramework.LightPlatformTestCase;
import com.intellij.testFramework.PlatformTestUtil;

import java.util.concurrent.*;

import static dev.ideamcp.protocol.Json.object;

public final class PlatformOperationServiceTest extends LightPlatformTestCase {
    public void testFullMcpResultAndToolErrorsArePreserved() throws Exception {
        PlatformOperationService service = new PlatformOperationService();
        try {
            var submitted = service.submit("fixture", () -> object("content", new Object[]{object("type", "text", "text", "fixture-result")}, "isError", true));
            String id = submitted.get("operationId").getAsString();
            PlatformTestUtil.waitWithEventsDispatching("operation", () -> !service.query(object("operationId", id), false).get("inProgress").getAsBoolean(), 10);
            var result = service.query(object("operationId", id), false);
            assertEquals("failed", result.get("phase").getAsString());
            assertTrue(result.getAsJsonObject("result").get("isError").getAsBoolean());
            assertTrue(result.toString().contains("fixture-result"));
        } finally {
            service.dispose();
        }
    }

    public void testCancellationDoesNotClaimThatUncooperativeWorkHasStopped() throws Exception {
        PlatformOperationService service = new PlatformOperationService();
        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            var submitted = service.submit("fixture", () -> {
                running.countDown();
                boolean done = false;
                while (!done) try {
                    done = release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                return object("isError", false);
            });
            String id = submitted.get("operationId").getAsString();
            assertTrue(running.await(5, TimeUnit.SECONDS));
            var cancelled = service.query(object("operationId", id), true);
            assertEquals("cancel_requested", cancelled.get("phase").getAsString());
            assertTrue(cancelled.get("inProgress").getAsBoolean());
            release.countDown();
            PlatformTestUtil.waitWithEventsDispatching("operation", () -> !service.query(object("operationId", id), false).get("inProgress").getAsBoolean(), 10);
            assertTrue(service.query(object("operationId", id), false).get("cancelRequested").getAsBoolean());
        } finally {
            release.countDown();
            service.dispose();
        }
    }
}
