package dev.romulus_lanceues.discord;

import dev.romulus_lanceues.ai.ChatService;
import dev.romulus_lanceues.memory.ConversationManager;
import dev.romulus_lanceues.memory.ConversationSession;
import dev.romulus_lanceues.memory.ConversationTurn;
import dev.romulus_lanceues.util.MessageSplitter;
import dev.romulus_lanceues.util.RateLimiter;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageReference;
import net.dv8tion.jda.api.entities.MessageType;
import net.dv8tion.jda.api.entities.SelfUser;
import net.dv8tion.jda.api.events.channel.update.ChannelUpdateArchivedEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

public class ConversationListener extends ListenerAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(ConversationListener.class);

    private final ConversationManager conversationManager;
    private final ChatService chatService;
    private final RateLimiter rateLimiter;
    private final ScheduledExecutorService scheduler;

    public ConversationListener(ConversationManager conversationManager,
                                ChatService chatService,
                                RateLimiter rateLimiter,
                                ScheduledExecutorService scheduler) {
        this.conversationManager = Objects.requireNonNull(conversationManager, "Conversation Manager cannot be null");
        this.chatService = Objects.requireNonNull(chatService, "Chat Service cannot be null");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "Rate Limit cannot be null");
        this.scheduler = Objects.requireNonNull(scheduler, "Scheduler cannot be null");
    }

    static final String SESSION_UNAVAILABLE = "This conversation is no longer active. Please start a new conversation.";
    static final String INSERT_FAILED = "I couldn't add your message to the conversation history. Please try again.";
    static final String GENERATION_FAILED = "I couldn't generate a response right now. Please try again.";
    static final String DELIVERY_FAILED = "I couldn't send the complete response. Some parts may already be visible.";
    static final String RECORDING_FAILED = "Your answer was sent, but I couldn't save it to the conversation history.";

    @Override
    public void onMessageReceived(@Nonnull MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;
        if (!event.getChannelType().isThread()) return;

        long sessionId = event.getChannel().getIdLong();
        Optional<ConversationSession> retrievedSession = conversationManager.getSession(sessionId);
        if (retrievedSession.isEmpty()) return;
        ConversationSession session = retrievedSession.get();

        SelfUser cloud = event.getJDA().getSelfUser();
        Message incomingMessage = event.getMessage();
        boolean mentionsCloud = incomingMessage.getMentions().isMentioned(cloud, Message.MentionType.USER);
        if (mentionsCloud) {
            handleAddressedMessage(incomingMessage, cloud, session);
        } else {
            handleUnmentionedMessage(incomingMessage, cloud, session);
        }
    }

    @Override
    public void onChannelUpdateArchived(@Nonnull ChannelUpdateArchivedEvent event) {
        if (Boolean.TRUE.equals(event.getNewValue())) {
            conversationManager.removeSession(event.getChannel().getIdLong());
        }
    }

    private void handleUnmentionedMessage(Message incomingMessage, SelfUser cloud, ConversationSession session) {
        if (incomingMessage.getType() != MessageType.INLINE_REPLY) {
            recordPassiveMessage(incomingMessage, session.getThreadId());
            return;
        }

        Message referencedMessage = incomingMessage.getReferencedMessage();
        if (referencedMessage != null) {
            handleResolvedReply(incomingMessage, referencedMessage, cloud, session);
            return;
        }

        MessageReference reference = incomingMessage.getMessageReference();
        if (reference == null) {
            recordPassiveMessage(incomingMessage, session.getThreadId());
            return;
        }

        CompletableFuture<Message> retrieval;
        try {
            retrieval = reference.resolve().submit();
        } catch (RuntimeException failure) {
            LOG.debug("Could not retrieve reply target in thread {}", session.getThreadId(), failure);
            handleResolvedReply(incomingMessage, null, cloud, session);
            return;
        }

        // Defer routing and insertion until the reply target is known. No generation guard is held here.
        retrieval.whenComplete((resolved, failure) -> {
            if (failure != null) {
                LOG.debug("Could not retrieve reply target in thread {}", session.getThreadId(), failure);
                handleResolvedReply(incomingMessage, null, cloud, session);
            } else {
                handleResolvedReply(incomingMessage, resolved, cloud, session);
            }
        });
    }

    private void handleResolvedReply(Message incomingMessage, Message referencedMessage,
                                     SelfUser cloud, ConversationSession session) {
        // An asynchronous lookup must not route an old message into a replacement session.
        if (conversationManager.getSession(session.getThreadId()).orElse(null) != session) return;

        if (referencedMessage != null && referencedMessage.getAuthor().getId().equals(cloud.getId())) {
            handleAddressedMessage(incomingMessage, cloud, session);
        } else {
            recordPassiveMessage(incomingMessage, session.getThreadId());
        }
    }

    private void recordPassiveMessage(Message incomingMessage, long sessionId) {
        String content = incomingMessage.getContentRaw();
        if (content.isBlank()) return;

        try {
            conversationManager.insertSessionTurn(sessionId,
                    ConversationTurn.user(incomingMessage.getAuthor().getName(), content));
        } catch (IllegalStateException failure) {
            // Expiration or removal can happen after the listener's session lookup.
            LOG.debug("Skipped passive message for unavailable conversation in thread {}", sessionId);
        } catch (RuntimeException failure) {
            LOG.error("Could not record passive message in thread {}", sessionId, failure);
        }
    }

    private void handleAddressedMessage(Message incomingMessage, SelfUser cloud, ConversationSession session) {
        long sessionId = session.getThreadId();
        String username = incomingMessage.getAuthor().getName();
        String messageContent = incomingMessage.getContentRaw()
                .replace("<@" + cloud.getId() + ">", "")
                .replace("<@!" + cloud.getId() + ">", "")
                .strip();

        if(messageContent.isBlank()){
            replySafely(incomingMessage, session, "Hello, " + username + "! What would you like to ask?");
            return;
        }

        Optional<Duration> cooldown = rateLimiter.checkCooldown(incomingMessage.getAuthor().getIdLong());
        if (cooldown.isPresent()) {
            long remainingSeconds = Math.max(1, (long) Math.ceil(cooldown.get().toMillis() / 1000.0));
            replySafely(incomingMessage, session,
                    String.format("Please wait %d seconds before asking another question.", remainingSeconds));
            return;
        }

        if (!session.tryAcquireLock()) {
            replySafely(incomingMessage, session, "I'm already preparing a response. Please try again after I finish.");
            return;
        }

        // Failures here identify whether the incoming message was accepted at all.
        try {
            conversationManager.insertSessionTurn(sessionId, ConversationTurn.user(username, messageContent));
        } catch (IllegalStateException failure) {
            try {
                reportFailure(incomingMessage, session, SESSION_UNAVAILABLE, failure);
            } finally {
                session.releaseLock();
            }
            return;
        } catch (RuntimeException failure) {
            try {
                reportFailure(incomingMessage, session, INSERT_FAILED, failure);
            } finally {
                session.releaseLock();
            }
            return;
        }

        TypingIndicator typing = new TypingIndicator(incomingMessage.getChannel(), conversationManager, session, scheduler);
        try {
            typing.start();
            // This handler sees only generation failures, not downstream delivery failures.
            CompletableFuture<Void> workflow = chatService.reply(session.getActiveTurns())
                    .whenComplete((result, failure) -> {
                        if (failure != null) reportFailure(incomingMessage, session, GENERATION_FAILED, failure);
                    })
                    .thenCompose(result -> deliverAndRecord(incomingMessage, session, result));

            // Each stage reports its own failure; cleanup must also run on success.
            workflow.whenComplete((ignored, failure) -> {
                try {
                    typing.close();
                } finally {
                    session.releaseLock();
                }
            });
        } catch (RuntimeException failure) {
            // The service may throw before returning a future.
            try {
                reportFailure(incomingMessage, session, GENERATION_FAILED, failure);
            } finally {
                try {
                    typing.close();
                } finally {
                    session.releaseLock();
                }
            }
        }
    }

    private CompletableFuture<Void> deliverAndRecord(Message incomingMessage, ConversationSession session, String result) {
        if (!canRespond(session)) return CompletableFuture.completedFuture(null);
        List<String> chunks = MessageSplitter.split(result, 2000);
        if (chunks.isEmpty()) {
            IllegalStateException failure = new IllegalStateException("The generated response was empty");
            reportFailure(incomingMessage, session, GENERATION_FAILED, failure);
            return CompletableFuture.failedFuture(failure);
        }

        CompletableFuture<?> delivery = CompletableFuture.completedFuture(null);
        for (String chunk : chunks) {
            delivery = delivery.thenCompose(ignored -> {
                synchronized (session) {
                    if (!canRespond(session)) return CompletableFuture.completedFuture(null);
                    return incomingMessage.reply(chunk)
                            .mentionRepliedUser(false)
                            .setAllowedMentions(Collections.emptyList())
                            .setCheck(() -> canRespond(session))
                            .submit();
                }
            });
        }

        return delivery.whenComplete((ignored, failure) -> {
            if (failure != null) reportFailure(incomingMessage, session, DELIVERY_FAILED, failure);
        }).thenRun(() -> {
            synchronized (session) {
                if (!canRespond(session)) return;
                try {
                    String deliveredText = String.join("\n", chunks);
                    conversationManager.insertSessionTurn(session.getThreadId(), ConversationTurn.bot(deliveredText));
                } catch (RuntimeException failure) {
                    reportFailure(incomingMessage, session, RECORDING_FAILED, failure);
                    throw failure;
                }
            }
        });
    }

    private boolean canRespond(ConversationSession session) {
        synchronized (session) {
            return conversationManager.getSession(session.getThreadId()).orElse(null) == session
                    && session.verifyStateEligibility(ConversationManager.TIME_TO_LIVE_DURATION);
        }
    }

    private void reportFailure(Message incomingMessage, ConversationSession session, String userMessage, Throwable failure) {
        LOG.error("Conversation operation failed in thread {}", incomingMessage.getChannel().getIdLong(), failure);
        replySafely(incomingMessage, session, userMessage);
    }

    private void replySafely(Message incomingMessage, ConversationSession session, String text) {
        synchronized (session) {
            if (!canRespond(session)) return;
            try {
                incomingMessage.reply(text)
                        .mentionRepliedUser(false)
                        .setAllowedMentions(Collections.emptyList())
                        .setCheck(() -> canRespond(session))
                        .queue(ignored -> {}, failure ->
                                LOG.warn("Could not send conversation notification", failure));
            } catch (RuntimeException failure) {
                LOG.warn("Could not submit conversation notification", failure);
            }
        }
    }
}
