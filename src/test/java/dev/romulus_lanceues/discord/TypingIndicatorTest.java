package dev.romulus_lanceues.discord;

import dev.romulus_lanceues.memory.ConversationManager;
import dev.romulus_lanceues.memory.ConversationSession;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.requests.RestAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TypingIndicatorTest {
    private static final long THREAD_ID = 123L;
    private static final Instant START = Instant.parse("2026-01-01T12:00:00Z");
    private Clock clock;
    private ConversationManager manager;
    private ConversationSession session;
    private MessageChannel channel;
    private RestAction<Void> action;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> task;
    private TypingIndicator indicator;
    private Runnable tick;
    private BooleanSupplier dispatchCheck;
    private Consumer<Void> success;
    private Consumer<Throwable> failure;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        clock = spy(Clock.fixed(START, ZoneOffset.UTC));
        scheduler = mock(ScheduledExecutorService.class);
        manager = new ConversationManager(clock, scheduler);
        session = manager.createSession(THREAD_ID, "Initial instructions", "Alice");
        channel = mock(MessageChannel.class);
        action = mock(RestAction.class, RETURNS_SELF);
        when(channel.sendTyping()).thenReturn(action);
        when(action.setCheck(any())).thenAnswer(call -> {
            dispatchCheck = call.getArgument(0);
            return action;
        });
        doAnswer(call -> {
            success = call.getArgument(0);
            failure = call.getArgument(1);
            return null; // Tests decide when Discord finishes the typing request.
        }).when(action).queue(any(), any());
        task = mock(ScheduledFuture.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), eq(5L), eq(5L), eq(TimeUnit.SECONDS)))
                .thenAnswer(call -> {
                    tick = call.getArgument(0);
                    return task;
                });
        indicator = new TypingIndicator(channel, manager, session, scheduler);
    }

    @ParameterizedTest
    @ValueSource(strings = {"channel", "manager", "session", "scheduler"})
    void requiresDependencies(String missing) {
        assertThrows(NullPointerException.class, () -> new TypingIndicator(
                missing.equals("channel") ? null : channel,
                missing.equals("manager") ? null : manager,
                missing.equals("session") ? null : session,
                missing.equals("scheduler") ? null : scheduler));
        verifyNoInteractions(channel, scheduler);
    }

    @Test
    void sendsImmediatelyAndRefreshesEveryFiveSeconds() {
        indicator.start();
        verify(channel).sendTyping();
        assertTrue(dispatchCheck.getAsBoolean());
        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(5L), eq(5L), eq(TimeUnit.SECONDS));
        success.accept(null);

        tick.run();
        success.accept(null);
        tick.run();

        verify(channel, times(3)).sendTyping();
        verify(task, never()).cancel(anyBoolean());
        indicator.close();
    }

    @Test
    void typingRefreshDoesNotRefreshConversationActivityOrModifyHistory() {
        var initialContext = session.getActiveTurns();
        indicator.start();
        success.accept(null);
        when(clock.instant()).thenReturn(START.plusSeconds(5 * 60));

        tick.run();

        assertEquals(START, session.getLastActivity());
        assertEquals(initialContext, session.getActiveTurns());
        assertFalse(session.tryAcquireLock());
        indicator.close();
    }

    @Test
    void repeatedStartsDoNotSendOrScheduleDuplicateRefreshes() {
        indicator.start();
        success.accept(null);
        indicator.start();
        indicator.start();

        verify(channel).sendTyping();
        verify(scheduler, times(1)).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
        indicator.close();
    }

    @Test
    void closeBeforeStartPreventsAllWork() {
        indicator.close();
        indicator.start();
        indicator.close();
        verifyNoInteractions(channel, scheduler, task);
    }

    @Test
    void closingIsIdempotentAndDisablesQueuedAndFutureTypingWithoutOwningSchedulerOrGuard() {
        indicator.start();
        BooleanSupplier queuedCheck = dispatchCheck;
        Consumer<Void> lateSuccess = success;
        indicator.close();
        indicator.close();

        assertFalse(queuedCheck.getAsBoolean());
        lateSuccess.accept(null);
        tick.run();
        indicator.start();

        verify(channel).sendTyping();
        verify(task, times(1)).cancel(false);
        verify(scheduler, never()).shutdown();
        verify(scheduler, never()).shutdownNow();
        assertFalse(session.tryAcquireLock(), "Typing cleanup must preserve the response owner's guard");
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "removed", "expired", "replacement"})
    void unavailableSessionDoesNotStartTypingOrScheduleRefresh(String reason) {
        retireSession(reason);
        indicator.start();
        verifyNoInteractions(channel, scheduler, task);
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "removed", "expired", "replacement"})
    void retirementDisablesQueuedTypingAndCancelsTheNextRefresh(String reason) {
        indicator.start();
        BooleanSupplier queuedCheck = dispatchCheck;
        retireSession(reason);

        assertFalse(queuedCheck.getAsBoolean());
        tick.run();
        tick.run();
        indicator.close();

        verify(channel).sendTyping();
        verify(task, times(1)).cancel(false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"creation", "submission", "callback"})
    void typingFailuresAllowRetryWithoutEscapingOrCancelingRefresh(String stage) {
        RuntimeException error = new IllegalStateException("Typing unavailable");
        if (stage.equals("creation")) when(channel.sendTyping()).thenThrow(error).thenReturn(action);
        if (stage.equals("submission")) {
            doThrow(error).doAnswer(call -> {
                success = call.getArgument(0);
                failure = call.getArgument(1);
                return null;
            }).when(action).queue(any(), any());
        }

        assertDoesNotThrow(indicator::start);
        if (stage.equals("callback")) failure.accept(error);
        assertDoesNotThrow(() -> tick.run());

        verify(channel, times(2)).sendTyping();
        assertTrue(dispatchCheck.getAsBoolean());
        verify(task, never()).cancel(anyBoolean());
        assertFalse(session.tryAcquireLock());
        indicator.close();
    }

    @Test
    void pendingRequestPreventsBacklogUntilItsCallbackCompletes() {
        indicator.start();
        tick.run();
        tick.run();
        verify(channel).sendTyping();

        success.accept(null);
        tick.run();

        verify(channel, times(2)).sendTyping();
        indicator.close();
    }

    @Test
    void schedulingRejectionStillAllowsClosingTheImmediateTypingRequest() {
        when(scheduler.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any()))
                .thenThrow(new RejectedExecutionException("Scheduler stopped"));

        assertDoesNotThrow(indicator::start);
        assertTrue(dispatchCheck.getAsBoolean());
        assertDoesNotThrow(indicator::close);

        assertFalse(dispatchCheck.getAsBoolean());
        verifyNoInteractions(task);
        verify(scheduler, never()).shutdown();
        assertFalse(session.tryAcquireLock());
    }

    @Test
    void lateCallbackFromClosedIndicatorCannotReleaseAnotherIndicatorsPendingRequest() {
        indicator.start();
        Consumer<Void> oldSuccess = success;
        BooleanSupplier oldCheck = dispatchCheck;
        indicator.close();
        TypingIndicator next = new TypingIndicator(channel, manager, session, scheduler);
        next.start();
        Runnable nextTick = tick;
        BooleanSupplier nextCheck = dispatchCheck;

        oldSuccess.accept(null);
        nextTick.run();

        assertFalse(oldCheck.getAsBoolean());
        assertTrue(nextCheck.getAsBoolean());
        verify(channel, times(2)).sendTyping();
        next.close();
    }

    @Test
    void concurrentStartsCreateOnlyOneTypingRequestAndTask() throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Runnable start = () -> {
                ready.countDown();
                await(go);
                indicator.start();
            };
            var first = workers.submit(start);
            var second = workers.submit(start);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);

            verify(channel).sendTyping();
            verify(scheduler, times(1)).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
        } finally {
            go.countDown();
            indicator.close();
            workers.shutdownNow();
        }
    }

    @Test
    void closingDuringTaskRegistrationCancelsTheRegisteredTask() throws Exception {
        CountDownLatch registering = new CountDownLatch(1);
        CountDownLatch allowRegistration = new CountDownLatch(1);
        CountDownLatch closing = new CountDownLatch(1);
        when(scheduler.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any())).thenAnswer(call -> {
            registering.countDown();
            await(allowRegistration);
            return task;
        });
        var workers = Executors.newFixedThreadPool(2);
        try {
            var starting = workers.submit(indicator::start);
            assertTrue(registering.await(5, TimeUnit.SECONDS));
            var stopping = workers.submit(() -> {
                closing.countDown();
                indicator.close();
            });
            assertTrue(closing.await(5, TimeUnit.SECONDS));
            allowRegistration.countDown();
            starting.get(5, TimeUnit.SECONDS);
            stopping.get(5, TimeUnit.SECONDS);

            verify(task).cancel(false);
            assertFalse(dispatchCheck.getAsBoolean());
        } finally {
            allowRegistration.countDown();
            indicator.close();
            workers.shutdownNow();
        }
    }

    private void retireSession(String reason) {
        switch (reason) {
            case "inactive" -> session.makeSessionInactive();
            case "removed" -> manager.removeSession(THREAD_ID);
            case "expired" -> when(clock.instant()).thenReturn(START.plusSeconds(45 * 60 + 1));
            case "replacement" -> {
                manager.removeSession(THREAD_ID);
                manager.createSession(THREAD_ID, "New instructions", "Bob");
            }
            default -> throw new IllegalArgumentException(reason);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for test coordination");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Test worker interrupted", interrupted);
        }
    }
}
