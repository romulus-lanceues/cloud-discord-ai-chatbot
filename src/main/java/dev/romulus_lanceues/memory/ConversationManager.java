package dev.romulus_lanceues.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;

public class ConversationManager implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ConversationManager.class);
    public static final Duration TIME_TO_LIVE_DURATION= Duration.ofMinutes(45);

    private final Clock clock;
    private final ConcurrentHashMap<Long, ConversationSession> sessions;
    private final ScheduledExecutorService executor;
    private ScheduledFuture<?> cleanUpTask;

    public ConversationManager(Clock clock, ScheduledExecutorService executor) {
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
        this.sessions = new ConcurrentHashMap<>();
        this.executor = Objects.requireNonNull(executor, "Executor must not be null");
    }

    public ConversationSession createSession(long threadId, String prompt, String initialAuthor){
        Objects.requireNonNull(prompt, "Prompt cannot be null");
        Objects.requireNonNull(initialAuthor, "Initial Author cannot be null");
        if(prompt.isBlank()) throw new IllegalArgumentException("Prompt cannot be empty");
        if(initialAuthor.isBlank()) throw new IllegalArgumentException("Initial Author cannot be empty");

        ConversationTurn initialPrompt = new ConversationTurn(initialAuthor, prompt, clock.instant(), false);
        ConversationSession session = new ConversationSession(threadId, initialPrompt, clock);
        ConversationSession insertionResult = sessions.putIfAbsent(threadId, session);
        //Overriding an existing session is not allowed
        if(insertionResult != null) throw new IllegalStateException("Session already exists");

        return session;
    }

     public void insertSessionTurn(long threadId, ConversationTurn turn){
        Objects.requireNonNull(turn, "Turn cannot be null");

        Optional<ConversationSession> retrievedSession = getSession(threadId);
        if(retrievedSession.isEmpty()) throw new IllegalStateException("No session exists");

        ConversationSession session = retrievedSession.get();
        session.insertConversationTurn(turn, TIME_TO_LIVE_DURATION);
    }

    public Optional<ConversationSession> getSession(long threadId) {

        return Optional.ofNullable(sessions.get(threadId));
    }

    public void removeSession(long threadId){
        Optional<ConversationSession> session = getSession(threadId);

        if(session.isPresent()){
            ConversationSession sessionToRemove = session.get();
            sessionToRemove.makeSessionInactive();
            sessions.remove(threadId, sessionToRemove);
        }
    }

    public int evictExpiredSessions(){
        int evictionCounter = 0;
        for(ConcurrentHashMap.Entry<Long, ConversationSession> entry : sessions.entrySet()){
            if(!entry.getValue().verifyStateEligibility(TIME_TO_LIVE_DURATION)){
                boolean evictionResult = sessions.remove(entry.getKey(), entry.getValue());
                if(evictionResult) evictionCounter++;
            }
        }
        return evictionCounter;
    }

    public synchronized void initiateScheduledTasks(){
        //Check if the existing executor was ordered to shut down
        if(executor.isShutdown()) return;

        //Reject if there's an existing clean up task
        if(cleanUpTask != null) return;

        cleanUpTask = executor.scheduleWithFixedDelay(
                () -> {
                    try {
                       int sessionsEvicted = evictExpiredSessions();
                        LOG.info("Expired sessions evicted count: {}", sessionsEvicted);
                    }
                    catch (RuntimeException e) {
                        LOG.error("Scheduled conversation cleanup failed:", e);
                    }
                },
                5L,
                5L,
                TimeUnit.MINUTES
        );

    }

    @Override
    public synchronized void close(){
        executor.shutdown();
    }

}
