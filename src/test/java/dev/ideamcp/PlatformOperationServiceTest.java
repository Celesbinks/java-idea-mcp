package dev.ideamcp;

import com.intellij.testFramework.LightPlatformTestCase;
import com.intellij.testFramework.PlatformTestUtil;

import java.util.concurrent.*;

import static dev.ideamcp.protocol.Json.object;

public final class PlatformOperationServiceTest extends LightPlatformTestCase {
    public void testEditorDeadlineAndCancellationRemainPendingUntilFinalization() throws Exception {
        PlatformOperationService service = new PlatformOperationService(20);
        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            var submitted = service.submit("reformat_file", () -> {
                running.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException failure) { interrupted.set(true); throw failure; }
                PlatformOperationService.stage("flushing");
                return object("isError", false, "_meta", object(EditorWriteBoundary.META_KEY, object("status", "persisted")));
            });
            String id = submitted.get("operationId").getAsString();
            assertTrue(running.await(3, TimeUnit.SECONDS));
            PlatformTestUtil.waitWithEventsDispatching("advisory deadline", () -> service.query(object("operationId", id), false).get("deadlineExceeded").getAsBoolean(), 3);
            var cancelled = service.query(object("operationId", id), true);
            assertEquals("cancel_requested", cancelled.get("phase").getAsString());
            assertTrue(cancelled.get("inProgress").getAsBoolean());assertFalse(interrupted.get());
            release.countDown();
            PlatformTestUtil.waitWithEventsDispatching("editor finalization", () -> !service.query(object("operationId", id), false).get("inProgress").getAsBoolean(), 3);
            var result = service.query(object("operationId", id), false);
            assertEquals("cancelled", result.get("phase").getAsString());
            assertEquals("persisted", result.getAsJsonObject("editorWrite").get("status").getAsString());
        } finally { release.countDown(); service.dispose(); }
    }

    public void testQueuedEditorCancellationNeverRunsTheAction() throws Exception {
        PlatformOperationService service = new PlatformOperationService();
        CountDownLatch running = new CountDownLatch(4), release = new CountDownLatch(1);
        var edits = new java.util.concurrent.atomic.AtomicInteger();
        try {
            for (int i=0;i<4;i++) service.submit("fixture", () -> { running.countDown();release.await(5,TimeUnit.SECONDS);return object("isError",false); });
            assertTrue(running.await(3,TimeUnit.SECONDS));
            var queued = service.submit("create_new_file", () -> { edits.incrementAndGet();return object("isError",false); });
            var cancelled = service.query(object("operationId", queued.get("operationId").getAsString()), true);
            assertEquals("cancelled", cancelled.get("phase").getAsString());assertFalse(cancelled.get("inProgress").getAsBoolean());
            release.countDown();assertEquals(0,edits.get());
        } finally {release.countDown();service.dispose();}
    }

    public void testDisposeDoesNotInterruptAnActiveEditorSave() throws Exception {
        PlatformOperationService service = new PlatformOperationService();
        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            service.submit("apply_patch", () -> {
                running.countDown();
                try {release.await(5,TimeUnit.SECONDS);}catch(InterruptedException failure){interrupted.set(true);throw failure;}
                finished.countDown();return object("isError",false);
            });
            assertTrue(running.await(3,TimeUnit.SECONDS));service.dispose();assertFalse(interrupted.get());
            release.countDown();assertTrue(finished.await(3,TimeUnit.SECONDS));assertFalse(interrupted.get());
        } finally {release.countDown();service.dispose();}
    }
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
