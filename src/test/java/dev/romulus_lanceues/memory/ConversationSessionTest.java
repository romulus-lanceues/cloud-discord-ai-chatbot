package dev.romulus_lanceues.memory;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class ConversationSessionTest {

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

    @Test
    void expiredInsertionPreservesFullHistoryAndLastActivity() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T12:00:00Z"));
        ConversationSession session = new ConversationSession(123L, ConversationTurn.user("Alice", "Hello"), clock);
        for (int i = 1; i <= 7; i++) {
            session.insertConversationTurn(ConversationTurn.user("Alice", "Turn " + i), TTL);
        }
        List<ConversationTurn> history = session.getTurns();
        assertEquals(7, history.size());
        Instant lastActivity = session.getLastActivity();
        clock.advance(TTL.plusNanos(1));

        assertThrows(IllegalStateException.class,
                () -> session.insertConversationTurn(ConversationTurn.bot("Too late"), TTL));

        assertEquals(history, session.getTurns());
        assertEquals(lastActivity, session.getLastActivity());
        assertFalse(session.verifyStateEligibility(TTL));
    }

    @Test
    void inactiveSessionRemainsInactiveEvenWithLongerTtl() {
        ConversationSession session = new ConversationSession(123L, ConversationTurn.user("Alice", "Hello"), TEST_CLOCK);
        session.makeSessionInactive();

        assertFalse(session.verifyStateEligibility(Duration.ofDays(1)));
        assertThrows(IllegalStateException.class,
                () -> session.insertConversationTurn(ConversationTurn.bot("Rejected"), Duration.ofDays(1)));
        assertTrue(session.getTurns().isEmpty());
    }

    private static final Duration TTL = Duration.ofMinutes(45);

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void contextKeepsInitialDirectiveFirstAfterPruning() {
        ConversationTurn initial = new ConversationTurn("Alice", "Answer in Spanish", TEST_CLOCK.instant(), false);
        ConversationSession session = new ConversationSession(123L, initial, TEST_CLOCK);
        List<ConversationTurn> subsequentTurns = new ArrayList<>();
        for (int i = 1; i <= 23; i++) {
            ConversationTurn turn = ConversationTurn.user("Bob", "Message " + i);
            session.insertConversationTurn(turn, TTL);
            subsequentTurns.add(turn);
        }

        List<ConversationTurn> expected = new ArrayList<>();
        expected.add(initial);
        expected.addAll(subsequentTurns.subList(16, 23));
        assertEquals(expected, session.getActiveTurns());
        assertEquals(8, session.getActiveTurns().size());
        assertSame(initial, session.getActiveTurns().getFirst());
        assertEquals(7, session.getTurns().size());
        assertFalse(session.getTurns().contains(initial));
    }

    @Test
    void contextIsAnImmutableIndependentSnapshot() {
        ConversationTurn initial = ConversationTurn.user("Alice", "Answer in Spanish");
        ConversationSession session = new ConversationSession(123L, initial, TEST_CLOCK);
        List<ConversationTurn> snapshot = session.getActiveTurns();
        ConversationTurn reply = ConversationTurn.bot("Hola");

        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(reply));
        session.insertConversationTurn(reply, TTL);

        assertEquals(List.of(initial), snapshot);
        assertEquals(List.of(initial, reply), session.getActiveTurns());
    }

    @Test
    public void testSessionCreation(){
        long threadId = 423543241L;
        String initialPrompt = "Test Prompt";

        ConversationSession session = new ConversationSession(threadId, ConversationTurn.user("Alice", initialPrompt), TEST_CLOCK);

        assertEquals(threadId, session.getThreadId());
        assertEquals(initialPrompt, session.getInitialPrompt().content());
        assertEquals(TEST_CLOCK.instant(), session.getLastActivity());
        assertTrue(session.getTurns().isEmpty());
    }

    @Test
    public void rejectNullInitialPrompt(){
        assertThrows(NullPointerException.class, () -> new ConversationSession(21376176L, null, TEST_CLOCK));
    }


    @Test
    public void addsTurnToHistory(){
        ConversationTurn userTurn = ConversationTurn.user("Iroha", "What is a square root?");
        ConversationSession conversationSession = new ConversationSession(351531L, ConversationTurn.user("Alice", "Act as Maths tutor"), TEST_CLOCK);

        conversationSession.insertConversationTurn(userTurn, TTL);

        assertEquals(1, conversationSession.getTurns().size());
        assertEquals(userTurn, conversationSession.getTurns().getFirst());
    }

    @Test
    public void rejectNullTurnInsertion(){
        ConversationSession conversationSession = new ConversationSession(351531L, ConversationTurn.user("Alice", "Talk in Spanish"), TEST_CLOCK);

        assertThrows(NullPointerException.class, () -> conversationSession.insertConversationTurn(null, TTL));
    }

    @Test
    public void verifyInsertionOrder(){
        ConversationTurn conversationTurn1 = ConversationTurn.user("Dante", "What is a noun?");
        ConversationTurn conversationTurn2 = ConversationTurn.user("Sally", "What is a verb?");
        ConversationSession conversationSession = new ConversationSession(237127631L, ConversationTurn.user("Alice", "Act as an English tutor"), TEST_CLOCK);

        conversationSession.insertConversationTurn(conversationTurn1, TTL);
        conversationSession.insertConversationTurn(conversationTurn2, TTL);

        assertEquals(2, conversationSession.getTurns().size());
        assertEquals(conversationTurn1, conversationSession.getTurns().getFirst());
        assertEquals(conversationTurn2, conversationSession.getTurns().getLast());
    }

    @Test
    public void sevenRollingTurnsFitAlongsidePinnedPrompt(){
        ConversationSession conversationSession = new ConversationSession(351531L, ConversationTurn.user("Alice", "Test conversation"), TEST_CLOCK);
        List<ConversationTurn> expectedConversationTurns = new ArrayList<>();

        for(int i = 1; i <= 7 ; i++){
            ConversationTurn conversationTurn = ConversationTurn.user("Minju", "Prompt No." + i);
            conversationSession.insertConversationTurn(conversationTurn, TTL);
            expectedConversationTurns.add(conversationTurn);
        }

        assertEquals(7, conversationSession.getTurns().size());
        assertEquals(8, conversationSession.getActiveTurns().size());
        assertEquals("Prompt No.1", conversationSession.getTurns().getFirst().content());
        assertEquals("Prompt No.7", conversationSession.getTurns().getLast().content());
        assertEquals(expectedConversationTurns, conversationSession.getTurns());
    }

    @Test
    public void eighthRollingTurnEvictsOldestAndKeepsNewestSeven(){
        ConversationSession conversationSession =  new ConversationSession(237127631L, ConversationTurn.user("Alice", "Random chat"), TEST_CLOCK);
        List<ConversationTurn> newestSevenTurns = new ArrayList<>();

        for(int i = 1; i <= 8 ; i++){
            ConversationTurn conversationTurn =  ConversationTurn.user("Moka", "Prompt No." + i);
            conversationSession.insertConversationTurn(conversationTurn, TTL);
            newestSevenTurns.add(conversationTurn);
        }

        newestSevenTurns.removeFirst();

        assertEquals(7,  conversationSession.getTurns().size());
        assertEquals(8, conversationSession.getActiveTurns().size());
        assertSame(conversationSession.getInitialPrompt(), conversationSession.getActiveTurns().getFirst());
        assertEquals("Prompt No.2", conversationSession.getTurns().getFirst().content());
        assertEquals(newestSevenTurns,  conversationSession.getTurns());
    }

    @Test
    public void initialPromptIsPreserved(){
        ConversationSession conversationSession = new ConversationSession(237127631L, ConversationTurn.user("Alice", "Test conversation"), TEST_CLOCK);

        for(int i = 1; i <= 23 ; i++){
            conversationSession.insertConversationTurn(ConversationTurn.user("Wonhee", "Prompt No." + i), TTL);
        }

        assertEquals(7, conversationSession.getTurns().size());
        assertEquals("Test conversation", conversationSession.getInitialPrompt().content());
    }

    @Test
    public void rejectTurnModification(){
        ConversationSession conversationSession = new ConversationSession(27412768179L, ConversationTurn.user("Alice", "Test conversation"), TEST_CLOCK);

        ConversationTurn conversationTurn1 = ConversationTurn.user("Kurt", "Test prompt");
        ConversationTurn conversationTurn2 = ConversationTurn.user("cloud", "All apologies");

        conversationSession.insertConversationTurn(conversationTurn1, TTL);
        conversationSession.insertConversationTurn(conversationTurn2, TTL);

        List<ConversationTurn> conversationTurns = conversationSession.getTurns();

        assertThrows(UnsupportedOperationException.class, () -> conversationTurns.add( ConversationTurn.user("Jack", "Test prompt")));
    }

    @Test
    public void independentSnapshot(){
        ConversationTurn conversationTurn1 = ConversationTurn.user("Claire", "What is a RuntimeException?");
        ConversationSession conversationSession = new ConversationSession(2841819L, ConversationTurn.user("Alice", "Act as my Java tutor"), TEST_CLOCK);

        conversationSession.insertConversationTurn(conversationTurn1, TTL);

        List<ConversationTurn> initialConversationTurns = conversationSession.getTurns();

        assertEquals(1, initialConversationTurns.size());

        ConversationTurn conversationTurn2 = ConversationTurn.user("Flown", "What is a HashMap?");

        conversationSession.insertConversationTurn(conversationTurn2, TTL);

        assertEquals(1, initialConversationTurns.size());
        assertEquals(List.of(conversationTurn1), initialConversationTurns);
        assertEquals(2, conversationSession.getTurns().size());
        assertEquals(List.of(conversationTurn1, conversationTurn2), conversationSession.getTurns());
    }

    @Test
    public void acquisitionLock(){
        ConversationSession conversationSession = new ConversationSession(237127631L, ConversationTurn.user("Alice", "Test conversation"), TEST_CLOCK);

        //Lock acquired
        boolean result = conversationSession.tryAcquireLock();
        assertTrue(result);

        //Lock will be unavailable
        boolean result2 = conversationSession.tryAcquireLock();
        assertFalse(result2);

        //Lock gets released
        conversationSession.releaseLock();

        //Lock is available
        boolean result3 = conversationSession.tryAcquireLock();
        assertTrue(result3);
    }

    @Test
    public void expectSessionNotExpired(){
        MutableClock mutableClock = new MutableClock(Instant.parse("2026-01-01T12:00:00Z"));
        ConversationSession conversationSession = new ConversationSession(237127631L, ConversationTurn.user("Alice", "Test conversation"), mutableClock);

        mutableClock.advance(Duration.ofMinutes(10));

        assertTrue(conversationSession.verifyStateEligibility(TTL));
        assertEquals(Instant.parse("2026-01-01T12:10:00Z"), mutableClock.instant());

    }

    @Test
    public void stillNotExpiredAtExactly45Minutes(){
        MutableClock mutableClock = new MutableClock(Instant.parse("2026-01-01T12:00:00Z"));
        ConversationSession conversationSession = new ConversationSession(237127631L, ConversationTurn.user("Alice", "Test conversation"), mutableClock);

        mutableClock.advance(Duration.ofMinutes(45));

        assertTrue(conversationSession.verifyStateEligibility(TTL));
        assertEquals(Instant.parse("2026-01-01T12:45:00Z"), mutableClock.instant());
    }

    @Test
    public void expectSessionExpired(){
        MutableClock mutableClock = new MutableClock(Instant.parse("2026-01-01T12:00:00Z"));
        ConversationSession conversationSession = new ConversationSession(237127631L, ConversationTurn.user("Alice", "Test conversation"), mutableClock);

        mutableClock.advance(Duration.ofMinutes(45).plusNanos(1));

        assertFalse(conversationSession.verifyStateEligibility(TTL));
        assertEquals(Instant.parse("2026-01-01T12:45:00.000000001Z"), mutableClock.instant());
    }

    @Test
    public void activityResetsLastActivity(){
        MutableClock mutableClock = new MutableClock(Instant.parse("2026-01-01T12:00:00Z"));
        ConversationSession conversationSession = new ConversationSession(237127631L, ConversationTurn.user("Alice", "Test conversation"), mutableClock);

        assertEquals(Instant.parse("2026-01-01T12:00:00Z"), mutableClock.instant());

        //Advance clock to 30 minutes
        mutableClock.advance(Duration.ofMinutes(30));

        //New activity happened
        ConversationTurn conversationTurn = ConversationTurn.user("Noel", "Test prompt");
        conversationSession.insertConversationTurn(conversationTurn, TTL);

        assertEquals(Instant.parse("2026-01-01T12:30:00Z"), conversationSession.getLastActivity());

        //Advance clock for another 30 minutes
        mutableClock.advance(Duration.ofMinutes(30));

        //Make sure session is still not expired
        assertTrue(conversationSession.verifyStateEligibility(TTL));
        assertEquals(Instant.parse("2026-01-01T13:00:00Z"), mutableClock.instant());
    }

}
