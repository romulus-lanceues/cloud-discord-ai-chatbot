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

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC);

    @Test
    public void testSessionCreation(){
        long threadId = 423543241L;
        String initialPrompt = "Test Prompt";

        ConversationSession session = new ConversationSession(threadId, initialPrompt, TEST_CLOCK);

        assertEquals(threadId, session.getThreadId());
        assertEquals(initialPrompt, session.getInitialPrompt());
        assertNotNull(session.getLastActivity());
        assertTrue(session.getTurns().isEmpty());
    }

    @Test
    public void rejectNullInitialPrompt(){
        assertThrows(NullPointerException.class, () -> new ConversationSession(21376176L, null, TEST_CLOCK));
    }

    @Test
    public void rejectEmptyInitialPrompt(){
        assertThrows(IllegalArgumentException.class, () -> new ConversationSession(21376176L, "", TEST_CLOCK));
    }

    @Test
    public void addsTurnToHistory(){
        ConversationTurn userTurn = ConversationTurn.user("Iroha", "What is a square root?");
        ConversationSession conversationSession = new ConversationSession(351531L, "Act as Maths tutor", TEST_CLOCK);

        conversationSession.addTurn(userTurn);

        assertEquals(1, conversationSession.getTurns().size());
        assertEquals(userTurn, conversationSession.getTurns().getFirst());
    }

    @Test
    public void rejectNullTurnInsertion(){
        ConversationSession conversationSession = new ConversationSession(351531L, "Talk in Spanish", TEST_CLOCK);

        assertThrows(NullPointerException.class, () -> conversationSession.addTurn(null));
    }

    @Test
    public void verifyInsertionOrder(){
        ConversationTurn conversationTurn1 = ConversationTurn.user("Dante", "What is a noun?");
        ConversationTurn conversationTurn2 = ConversationTurn.user("Sally", "What is a verb?");
        ConversationSession conversationSession = new ConversationSession(237127631L, "Act as an English tutor", TEST_CLOCK);

        conversationSession.addTurn(conversationTurn1);
        conversationSession.addTurn(conversationTurn2);

        assertEquals(2, conversationSession.getTurns().size());
        assertEquals(conversationTurn1, conversationSession.getTurns().getFirst());
        assertEquals(conversationTurn2, conversationSession.getTurns().getLast());
    }

    @Test
    public void historyCap(){
        ConversationSession conversationSession = new ConversationSession(351531L, "Test conversation", TEST_CLOCK);
        List<ConversationTurn> expectedConversationTurns = new ArrayList<>();

        for(int i = 1; i <= 20 ; i++){
            ConversationTurn conversationTurn = ConversationTurn.user("Minju", "Prompt No." + i);
            conversationSession.addTurn(conversationTurn);
            expectedConversationTurns.add(conversationTurn);
        }

        assertEquals(20, conversationSession.getTurns().size());
        assertEquals("Prompt No.1", conversationSession.getTurns().getFirst().content());
        assertEquals("Prompt No.20", conversationSession.getTurns().getLast().content());
        assertEquals(expectedConversationTurns, conversationSession.getTurns());
    }

    @Test
    public void keepTheNewestTwentyTurn(){
        ConversationSession conversationSession =  new ConversationSession(237127631L, "Random chat", TEST_CLOCK);
        List<ConversationTurn> lastTwentyTurns = new ArrayList<>();

        for(int i = 1; i <= 21 ; i++){
            ConversationTurn conversationTurn =  ConversationTurn.user("Moka", "Prompt No." + i);
            conversationSession.addTurn(conversationTurn);
            lastTwentyTurns.add(conversationTurn);
        }

        lastTwentyTurns.removeFirst();

        assertEquals(20,  conversationSession.getTurns().size());
        assertEquals("Prompt No.2", conversationSession.getTurns().getFirst().content());
        assertEquals(lastTwentyTurns,  conversationSession.getTurns());
    }

    @Test
    public void initialPromptIsPreserved(){
        ConversationSession conversationSession = new ConversationSession(237127631L, "Test conversation", TEST_CLOCK);

        for(int i = 1; i <= 23 ; i++){
            conversationSession.addTurn(ConversationTurn.user("Wonhee", "Prompt No." + i));
        }

        assertEquals(20, conversationSession.getTurns().size());
        assertEquals("Test conversation", conversationSession.getInitialPrompt());
    }

    @Test
    public void rejectTurnModification(){
        ConversationSession conversationSession = new ConversationSession(27412768179L, "Test conversation", TEST_CLOCK);

        ConversationTurn conversationTurn1 = ConversationTurn.user("Kurt", "Test prompt");
        ConversationTurn conversationTurn2 = ConversationTurn.user("cloud", "All apologies");

        conversationSession.addTurn(conversationTurn1);
        conversationSession.addTurn(conversationTurn2);

        List<ConversationTurn> conversationTurns = conversationSession.getTurns();

        assertThrows(UnsupportedOperationException.class, () -> conversationTurns.add( ConversationTurn.user("Jack", "Test prompt")));
    }

    @Test
    public void independentSnapshot(){
        ConversationTurn conversationTurn1 = ConversationTurn.user("Claire", "What is a RuntimeException?");
        ConversationSession conversationSession = new ConversationSession(2841819L, "Act as my Java tutor", TEST_CLOCK);

        conversationSession.addTurn(conversationTurn1);

        List<ConversationTurn> initialConversationTurns = conversationSession.getTurns();

        assertEquals(1, initialConversationTurns.size());

        ConversationTurn conversationTurn2 = ConversationTurn.user("Flown", "What is a HashMap?");

        conversationSession.addTurn(conversationTurn2);

        assertEquals(1, initialConversationTurns.size());
        assertEquals(List.of(conversationTurn1), initialConversationTurns);
        assertEquals(2, conversationSession.getTurns().size());
        assertEquals(List.of(conversationTurn1, conversationTurn2), conversationSession.getTurns());
    }

    @Test
    public void acquisitionLock(){
        ConversationSession conversationSession = new ConversationSession(237127631L, "Test conversation", TEST_CLOCK);

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
        ConversationSession conversationSession = new ConversationSession(237127631L, "Test conversation", mutableClock);

        mutableClock.advance(Duration.ofMinutes(10));

        assertFalse(conversationSession.isExpired(Duration.ofMinutes(45)));
        assertEquals(Instant.parse("2026-01-01T12:10:00Z"), mutableClock.instant());

    }

    @Test
    public void stillNotExpiredAtExactly45Minutes(){
        MutableClock mutableClock = new MutableClock(Instant.parse("2026-01-01T12:00:00Z"));
        ConversationSession conversationSession = new ConversationSession(237127631L, "Test conversation", mutableClock);

        mutableClock.advance(Duration.ofMinutes(45));

        assertFalse(conversationSession.isExpired(Duration.ofMinutes(45)));
        assertEquals(Instant.parse("2026-01-01T12:45:00Z"), mutableClock.instant());
    }

    @Test
    public void expectSessionExpired(){
        MutableClock mutableClock = new MutableClock(Instant.parse("2026-01-01T12:00:00Z"));
        ConversationSession conversationSession = new ConversationSession(237127631L, "Test conversation", mutableClock);

        mutableClock.advance(Duration.ofMinutes(45).plusNanos(1));

        assertTrue(conversationSession.isExpired(Duration.ofMinutes(45)));
        assertEquals(Instant.parse("2026-01-01T12:45:00.000000001Z"), mutableClock.instant());
    }

    @Test
    public void activityResetsLastActivity(){
        MutableClock mutableClock = new MutableClock(Instant.parse("2026-01-01T12:00:00Z"));
        ConversationSession conversationSession = new ConversationSession(237127631L, "Test conversation", mutableClock);

        assertEquals(Instant.parse("2026-01-01T12:00:00Z"), mutableClock.instant());

        //Advance clock to 30 minutes
        mutableClock.advance(Duration.ofMinutes(30));

        //New activity happened
        ConversationTurn conversationTurn = ConversationTurn.user("Noel", "Test prompt");
        conversationSession.addTurn(conversationTurn);

        assertEquals(Instant.parse("2026-01-01T12:30:00Z"), mutableClock.instant());

        //Advance clock for another 30 minutes
        mutableClock.advance(Duration.ofMinutes(30));

        //Make sure session is still not expired
        assertFalse(conversationSession.isExpired(Duration.ofMinutes(45)));
        assertEquals(Instant.parse("2026-01-01T13:00:00Z"), mutableClock.instant());
    }

}
