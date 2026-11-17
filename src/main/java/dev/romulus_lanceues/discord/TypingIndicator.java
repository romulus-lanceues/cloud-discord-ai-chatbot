package dev.romulus_lanceues.discord;

import dev.romulus_lanceues.memory.ConversationManager;
import dev.romulus_lanceues.memory.ConversationSession;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Periodic typing for one conversation response. Closing it does not close the shared scheduler. */
public final class TypingIndicator implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(TypingIndicator.class);
    private final MessageChannel channel;
    private final ConversationManager manager;
    private final ConversationSession session;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean pending = new AtomicBoolean();
    private ScheduledFuture<?> refreshTask;

    public TypingIndicator(MessageChannel channel, ConversationManager manager,
                           ConversationSession session, ScheduledExecutorService scheduler) {
        this.channel = Objects.requireNonNull(channel, "Channel cannot be null");
        this.manager = Objects.requireNonNull(manager, "Manager cannot be null");
        this.session = Objects.requireNonNull(session, "Session cannot be null");
        this.scheduler = Objects.requireNonNull(scheduler, "Scheduler cannot be null");
    }

    public void start() {
        if (!started.compareAndSet(false, true)) return;
        refresh();
        synchronized (this) {
            if (!active.get()) return;
            try {
                refreshTask = scheduler.scheduleWithFixedDelay(this::refresh, 5, 5, TimeUnit.SECONDS);
            } catch (RuntimeException failure) {
                LOG.warn("Could not schedule typing refresh in thread {}", session.getThreadId(), failure);
            }
        }
    }

    private boolean canRespond() {
        synchronized (session) {
            return manager.getSession(session.getThreadId()).orElse(null) == session
                    && session.verifyStateEligibility(ConversationManager.TIME_TO_LIVE_DURATION);
        }
    }

    private void refresh() {
        if (!active.get()) return;
        if (!canRespond()) {
            close();
            return;
        }
        synchronized (session) {
            if (!active.get() || !canRespond() || !pending.compareAndSet(false, true)) return;
            try {
                channel.sendTyping()
                        .setCheck(() -> active.get() && canRespond())
                        .queue(ignored -> pending.set(false), failure -> {
                            pending.set(false);
                            LOG.warn("Could not send typing indication in thread {}", session.getThreadId(), failure);
                        });
            } catch (RuntimeException failure) {
                pending.set(false);
                LOG.warn("Could not submit typing indication in thread {}", session.getThreadId(), failure);
            }
        }
    }

    @Override
    public synchronized void close() {
        if (!active.getAndSet(false)) return;
        if (refreshTask != null) refreshTask.cancel(false);
    }
}
