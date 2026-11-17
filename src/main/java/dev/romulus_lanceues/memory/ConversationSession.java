package dev.romulus_lanceues.memory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConversationSession {
    private static final int MAX_HISTORY_SIZE = 7; // Plus the pinned initial prompt: 8 total turns.

    private final long threadId;
    private final ConversationTurn initialPrompt;
    private final ArrayDeque<ConversationTurn> turns;
    private final AtomicBoolean isProcessing;
    private final Clock clock;
    private volatile Instant lastActivity;
    private boolean isActive;


    public ConversationTurn getInitialPrompt() {
        return initialPrompt;
    }


    public long getThreadId() {
        return threadId;
    }

    public Instant getLastActivity() {
        return lastActivity;
    }

    public ConversationSession(long threadId, ConversationTurn initialPrompt, Clock clock) {
        this.threadId = threadId;
        this.initialPrompt = Objects.requireNonNull(initialPrompt, "initialPrompt cannot be null");
        this.turns = new ArrayDeque<>(MAX_HISTORY_SIZE);
        this.isProcessing = new AtomicBoolean(false);
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.lastActivity = Instant.now(clock);
        this.isActive = true;

    }

    // Package-private: turn insertion is restricted to the memory package.
     synchronized void insertConversationTurn(ConversationTurn turn, Duration ttl) {
        boolean isActive = verifyStateEligibility(ttl);
        if(!isActive) throw new  IllegalStateException("Session has been inactive");
        addTurn(turn);
    }

    public synchronized List<ConversationTurn> getTurns() {
        return List.copyOf(turns);
    }

    public synchronized List<ConversationTurn> getActiveTurns() {
        List<ConversationTurn> conversationTurns = new ArrayList<>();

        conversationTurns.add(initialPrompt);

        conversationTurns.addAll(turns);

        return List.copyOf(conversationTurns);
    }

    public boolean tryAcquireLock(){
        return isProcessing.compareAndSet(false, true);
    }

    public void releaseLock() {
        isProcessing.set(false);
    }

    public synchronized void makeSessionInactive(){
        this.isActive = false;
    }

    public synchronized boolean verifyStateEligibility(Duration ttl) {
        boolean currentState = this.isActive;
        if(!currentState) return false;

        boolean isEligible = Duration.between(lastActivity, Instant.now(clock)).compareTo(ttl) > 0;
        if (isEligible) {
            makeSessionInactive();
            return false;
        }
        return  true;
    }

    private synchronized void addTurn(ConversationTurn turn) {
        Objects.requireNonNull(turn, "turn cannot be null");
        if(!isActive) throw new IllegalStateException("Session is already inactive");

        if(turns.size() >= MAX_HISTORY_SIZE) turns.removeFirst();

        turns.addLast(turn);
        lastActivity = Instant.now(clock);
    }

}
