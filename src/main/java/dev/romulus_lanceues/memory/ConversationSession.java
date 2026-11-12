package dev.romulus_lanceues.memory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConversationSession {
    private static final int MAX_HISTORY_SIZE = 20;

    private final long threadId;
    private final String initialPrompt;
    private final ArrayDeque<ConversationTurn> turns;
    private final AtomicBoolean isProcessing;
    private final Clock clock;
    private volatile Instant lastActivity;


    public String getInitialPrompt() {
        return initialPrompt;
    }

    public long getThreadId() {
        return threadId;
    }

    public Instant getLastActivity() {
        return lastActivity;
    }

    public ConversationSession(long threadId, String initialPrompt, Clock clock) {
        this.threadId = threadId;
        this.initialPrompt = Objects.requireNonNull(initialPrompt, "initialPrompt cannot be null");
        if(initialPrompt.isEmpty()) {
            throw new IllegalArgumentException("initialPrompt cannot be empty");
        }
        this.turns = new ArrayDeque<>(MAX_HISTORY_SIZE);
        this.isProcessing = new AtomicBoolean(false);
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.lastActivity = Instant.now(clock);

    }

    public synchronized void addTurn(ConversationTurn turn) {
        Objects.requireNonNull(turn, "turn cannot be null");
        if(turns.size() >= MAX_HISTORY_SIZE) {
            turns.removeFirst();
        }
        turns.addLast(turn);
        lastActivity = Instant.now(clock);
    }

    public synchronized List<ConversationTurn> getTurns() {
        return List.copyOf(turns);
    }

    public boolean tryAcquireLock(){
        return isProcessing.compareAndSet(false, true);
    }

    public void releaseLock() {
        isProcessing.set(false);
    }

    public boolean isExpired(Duration ttl) {
        return Duration.between(lastActivity, Instant.now(clock)).compareTo(ttl) > 0;
    }

}
