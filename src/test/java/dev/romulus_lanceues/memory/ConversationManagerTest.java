package dev.romulus_lanceues.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.*;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ConversationManagerTest {

    private static  class MutableClock extends Clock {

        private final AtomicReference<Instant> current;

        public MutableClock(Instant initial) {
            current = new AtomicReference<>(initial);
        }

        public void advance(Duration duration) {
            current.updateAndGet(current -> current.plus(duration));
        }

        @Override
        public Instant instant() {
            return current.get();
        }

        @Override
        public ZoneId  getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(current.get(), zone);
        }

    }

    @Mock
    private ScheduledExecutorService executor;

    @Mock
    private ScheduledFuture<?> cleanupTask;

    @Test
    void initialDirectivePreservesAuthorContentAndInjectedTime() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC);
        ConversationManager manager = new ConversationManager(clock, executor);

        ConversationSession session = manager.createSession(123L, "  Answer in Spanish  ", "Alice");

        ConversationTurn initial = session.getInitialPrompt();
        assertEquals("Alice", initial.speaker());
        assertEquals("Answer in Spanish", initial.content());
        assertEquals(clock.instant(), initial.timestamp());
        assertFalse(initial.isBot());
        assertEquals(java.util.List.of(initial), session.getActiveTurns());
        assertTrue(session.getTurns().isEmpty());
    }

    @Test
    void invalidAuthorIsRejectedWithoutRegisteringSession() {
        ConversationManager manager = new ConversationManager(Clock.systemUTC(), executor);

        assertThrows(NullPointerException.class, () -> manager.createSession(123L, "Hello", null));
        assertThrows(IllegalArgumentException.class, () -> manager.createSession(123L, "Hello", ""));
        assertThrows(IllegalArgumentException.class, () -> manager.createSession(123L, "Hello", "  "));
        assertTrue(manager.getSession(123L).isEmpty());
    }


    @Test
    void removedSessionReferenceCannotAcceptTurnsAndReplacementIsIndependent() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC);
        ConversationManager manager = new ConversationManager(clock, executor);
        ConversationSession removed = manager.createSession(123L, "Original", "Alice");
        manager.removeSession(123L);
        ConversationSession replacement = manager.createSession(123L, "Replacement", "Alice");

        assertThrows(IllegalStateException.class,
                () -> removed.insertConversationTurn(ConversationTurn.bot("Rejected"), Duration.ofMinutes(45)));
        ConversationTurn turn = ConversationTurn.user("Alice", "Hello again");
        manager.insertSessionTurn(123L, turn);

        assertTrue(removed.getTurns().isEmpty());
        assertSame(replacement, manager.getSession(123L).orElseThrow());
        assertEquals(java.util.List.of(turn), replacement.getTurns());
    }

    @Test
    void rejectedExpiredInsertionIsCleanedUpAndRetainedReferenceStaysInactive() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T12:00:00Z"));
        ConversationManager manager = new ConversationManager(clock, executor);
        ConversationSession session = manager.createSession(123L, "Hello", "Alice");
        Instant lastActivity = session.getLastActivity();
        clock.advance(Duration.ofMinutes(45).plusNanos(1));

        assertThrows(IllegalStateException.class,
                () -> manager.insertSessionTurn(123L, ConversationTurn.bot("Too late")));
        assertTrue(session.getTurns().isEmpty());
        assertEquals(lastActivity, session.getLastActivity());
        assertEquals(1, manager.evictExpiredSessions());
        assertTrue(manager.getSession(123L).isEmpty());
        assertThrows(IllegalStateException.class,
                () -> session.insertConversationTurn(ConversationTurn.bot("Still rejected"), Duration.ofDays(1)));
    }

    @Test
    void insertionIntoUnknownSessionIsRejected() {
        ConversationManager manager = new ConversationManager(Clock.systemUTC(), executor);
        assertThrows(IllegalStateException.class,
                () -> manager.insertSessionTurn(123L, ConversationTurn.user("Alice", "Hello")));
        assertTrue(manager.getSession(123L).isEmpty());
    }

    @Test
    public void creatingASessionReturnTheSameStoredSession(){
        ConversationManager conversationManager = new ConversationManager(Clock.systemUTC(), executor);
        long threadId = 356125123165L;
        String initialPrompt = "First ever session";

        ConversationSession createdSession = conversationManager.createSession( threadId, initialPrompt, "Alice");

        Optional<ConversationSession> storedSession = conversationManager.getSession(threadId);

        assertTrue(storedSession.isPresent());
        assertEquals(threadId, storedSession.get().getThreadId());
        assertEquals(initialPrompt, storedSession.get().getInitialPrompt().content());
        assertSame(createdSession, storedSession.get());
    }

    @Test
    public void lookingForAnUnknownSessionReturnsEmptySession(){
        ConversationManager conversationManager = new ConversationManager(Clock.systemUTC(), executor);
        long threadId = 356125123165L;

        Optional<ConversationSession> storedSession = conversationManager.getSession(threadId);

        assertTrue(storedSession.isEmpty());
    }

    @Test
    public void duplicateSessionThrowsAndPreservesTheOriginal(){
        ConversationManager conversationManager = new ConversationManager(Clock.systemUTC(), executor);
        long threadId = 356125123165L;
        String initialPrompt = "How to write in Russian?";

        ConversationSession createdSession = conversationManager.createSession( threadId, initialPrompt, "Alice");

        assertThrows(IllegalStateException.class, () -> conversationManager.createSession(threadId, "Duplicate!", "Alice"));

        Optional<ConversationSession> storedSession = conversationManager.getSession(threadId);

        assertTrue(storedSession.isPresent());
        assertEquals(threadId, storedSession.get().getThreadId());
        assertEquals(initialPrompt, storedSession.get().getInitialPrompt().content());
        assertSame(createdSession, storedSession.get());

    }

    @Test
    public void nullPromptIsRejected(){
        ConversationManager conversationManager = new ConversationManager(Clock.systemUTC(), executor);
        assertThrows(NullPointerException.class, () -> conversationManager.createSession(123, null, "Alice"));
    }

    @Test
    public void emptyPromptIsRejected(){
        ConversationManager conversationManager = new ConversationManager(Clock.systemUTC(), executor);
        assertThrows(IllegalArgumentException.class, () -> conversationManager.createSession(123, "", "Alice"));
    }

    @Test
    public void whiteSpaceValueIsRejected(){
        ConversationManager conversationManager = new ConversationManager(Clock.systemUTC(), executor);
        assertThrows(IllegalArgumentException.class, () -> conversationManager.createSession(123, "  ", "Alice"));
    }

    @Test
    public void removingASessionMakesItUnavailable(){
        ConversationManager conversationManager = new ConversationManager(Clock.systemUTC(), executor);

        //Create a session
        conversationManager.createSession(12323L, "Hello, Can you help me with Mathematics?", "Alice");

        Optional<ConversationSession> storedSession = conversationManager.getSession(12323L);
        assertTrue(storedSession.isPresent());

        //Remove it
        conversationManager.removeSession(12323L);

        //Assert that it's unavailable
        Optional<ConversationSession> retrievedSession = conversationManager.getSession(12323L);
        assertTrue(retrievedSession.isEmpty());
    }

    @Test
    public void removingAnUnknownThreadIdDoesNothing(){
        ConversationManager conversationManager = new ConversationManager(Clock.systemUTC(), executor);

        conversationManager.createSession(12113, "Hello, Can you help me with Physics?", "Alice");

        conversationManager.removeSession(776173127L);

        Optional<ConversationSession> storedSession = conversationManager.getSession(12113);
        assertTrue(storedSession.isPresent());
    }

    @Test
    public void sessionsWithDifferentIdsRemainIndependent(){
        ConversationManager conversationManager = new ConversationManager(Clock.systemUTC(), executor);

        conversationManager.createSession(123L, "Hello, Can you help me with Mathematics?", "Alice");
        conversationManager.createSession(456L, "Hello, Can you help me with Physics?", "Alice");

        Optional<ConversationSession> firstSession = conversationManager.getSession(123L);
        Optional<ConversationSession> secondSession= conversationManager.getSession(456L);

        assertTrue(firstSession.isPresent());

        conversationManager.insertSessionTurn(123L, ConversationTurn.bot("My name is cloud"));

        assertTrue(secondSession.isPresent());

        assertNotEquals(firstSession.get(), secondSession.get());
        assertNotEquals(firstSession.get().getTurns().size(), secondSession.get().getTurns().size());
        assertEquals(1,firstSession.get().getTurns().size());
        assertEquals(0,secondSession.get().getTurns().size());

    }

    @Test
    public void sessionEvictedIsZeroInAnEmptyManager(){
        MutableClock mutableClock = new MutableClock(Instant.parse("2018-04-01T00:00:00.00Z"));
        ConversationManager conversationManager = new ConversationManager(mutableClock, executor);


        assertEquals(0,conversationManager.evictExpiredSessions());
    }

    @Test
    public void sessionMustRemainDespiteEvictionProcess(){
        MutableClock mutableClock =  new MutableClock(Instant.parse("2018-04-01T00:00:00.00Z"));
        ConversationManager conversationManager = new ConversationManager(mutableClock, executor);
        conversationManager.createSession(123L, "Hello, Can you help me with Mathematics?", "Alice");

        assertEquals(0,conversationManager.evictExpiredSessions());
        assertTrue(conversationManager.getSession(123L).isPresent());
    }

    @Test
    public void sessionIsStillActiveAtExactly45Minutes(){
        MutableClock mutableClock =  new MutableClock(Instant.parse("2018-04-01T00:00:00.00Z"));
        ConversationManager conversationManager = new ConversationManager(mutableClock, executor);

        conversationManager.createSession(123L, "Hello, Can you help me with English?", "Alice");

        mutableClock.advance(Duration.ofMinutes(45));

        assertEquals(0,conversationManager.evictExpiredSessions());
        assertTrue(conversationManager.getSession(123L).isPresent());
    }

    @Test
    public void sessionIsEvictedPast45Minutes(){
        MutableClock mutableClock =  new MutableClock(Instant.parse("2018-04-01T00:00:00.00Z"));
        ConversationManager conversationManager = new ConversationManager(mutableClock, executor);

        conversationManager.createSession(123L, "Hello, Can you help me with Geometry?", "Alice");

        mutableClock.advance(Duration.ofMinutes(45).plusNanos(1));

        assertEquals(1,conversationManager.evictExpiredSessions());
        assertFalse(conversationManager.getSession(123L).isPresent());
    }

    @Test
    public void cleanUpValueReturnsZeroAfterInitialEviction(){
        MutableClock mutableClock = new MutableClock(Instant.parse("2018-04-01T00:00:00.00Z"));

        ConversationManager conversationManager = new ConversationManager(mutableClock, executor);

        conversationManager.createSession(123L, "Hello, Can you help me with Biology?", "Alice");

        mutableClock.advance(Duration.ofMinutes(45).plusNanos(1));

        assertEquals(1,conversationManager.evictExpiredSessions());
        assertEquals(0,conversationManager.evictExpiredSessions());
    }

    @Test
    public void onlyRemoveExpiredSession(){
        MutableClock mutableClock = new MutableClock(Instant.parse("2018-04-01T00:00:00.00Z"));
        ConversationManager conversationManager = new ConversationManager(mutableClock, executor);

        conversationManager.createSession(123L, "Hello, Can you help me with Astronomy?", "Alice");

        mutableClock.advance(Duration.ofMinutes(30));

        conversationManager.createSession(12345L, "Hello, Can you help me with Biology?", "Alice");

        mutableClock.advance(Duration.ofMinutes(16));

        assertEquals(1,conversationManager.evictExpiredSessions());
        assertTrue(conversationManager.getSession(12345L).isPresent());
        assertFalse(conversationManager.getSession(123L).isPresent());

    }

    @Test
    public void startupRegistersCleanUpWithFiveMinuteDelays(){
        try(ConversationManager conversationManager = new ConversationManager(Clock.systemUTC(), executor)){
            conversationManager.initiateScheduledTasks();
            verify(executor).scheduleWithFixedDelay(any(Runnable.class), eq(5L),  eq(5L), eq(TimeUnit.MINUTES));
        }
    }

    @Test
    public void cleanUpProcessDeclaredOnlyOnce(){
        try(ConversationManager conversationManager = new ConversationManager(Clock.systemUTC(), executor)){
            doReturn(cleanupTask).when(executor)
                    .scheduleWithFixedDelay(any(Runnable.class), eq(5L), eq(5L), eq(TimeUnit.MINUTES));

            conversationManager.initiateScheduledTasks();
            conversationManager.initiateScheduledTasks();

            verify(executor, times(1))
                    .scheduleWithFixedDelay(any(Runnable.class), eq(5L), eq(5L), eq(TimeUnit.MINUTES));
        }
    }

    @Test
    public void closingManagerShutsDownExecutor(){
        try(ConversationManager conversationManager = new ConversationManager(Clock.systemUTC(), executor)){}
        verify(executor).shutdown();
    }

    @Test
    public void startupSkipsSchedulingWhenExecutorIsShutdown(){
            try(ConversationManager conversationManager = new ConversationManager(Clock.systemUTC(), executor)){
                doReturn(true).when(executor).isShutdown();
                conversationManager.initiateScheduledTasks();

                verify(executor, never()).scheduleWithFixedDelay(any(Runnable.class), eq(5L), eq(5L), eq(TimeUnit.MINUTES));
            }

    }

    @Test
    public void scheduledCleanUpRemovesExpiredSessions(){
        MutableClock mutableClock = new  MutableClock(Instant.parse("2018-04-01T00:00:00.00Z"));
        ArgumentCaptor<Runnable> capturedTask = ArgumentCaptor.forClass(Runnable.class);

        try(ConversationManager conversationManager = new ConversationManager(mutableClock, executor)){
            conversationManager.createSession(123L, "Hello, Can you help me with Geometry?", "Alice");

            doReturn(cleanupTask).when(executor).scheduleWithFixedDelay(any(Runnable.class), eq(5L), eq(5L), eq(TimeUnit.MINUTES));

            conversationManager.initiateScheduledTasks();

            verify(executor).scheduleWithFixedDelay(capturedTask.capture(), eq(5L), eq(5L), eq(TimeUnit.MINUTES));

            mutableClock.advance(Duration.ofMinutes(46));

            capturedTask.getValue().run();

            assertTrue(conversationManager.getSession(123L).isEmpty());

        }
    }

    @Test
    public void cleanUpFailureDoesNotStopFutureCleanUps(){
        ArgumentCaptor<Runnable> capturedTask = ArgumentCaptor.forClass(Runnable.class);

        try(ConversationManager conversationManager = spy( new ConversationManager(Clock.systemUTC(), executor))){
            doThrow(new IllegalStateException("Simulated failure")).doReturn(0)
                    .when(conversationManager).evictExpiredSessions();

            conversationManager.initiateScheduledTasks();

            verify(executor).scheduleWithFixedDelay(capturedTask.capture(),  eq(5L), eq(5L), eq(TimeUnit.MINUTES));

            assertDoesNotThrow(() -> capturedTask.getValue().run());
            assertDoesNotThrow(() -> capturedTask.getValue().run());

            verify(conversationManager, times(2)).evictExpiredSessions();

        }
    }

}
