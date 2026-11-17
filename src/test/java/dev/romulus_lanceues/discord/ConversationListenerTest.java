package dev.romulus_lanceues.discord;

import dev.romulus_lanceues.ai.ChatService;
import dev.romulus_lanceues.memory.ConversationManager;
import dev.romulus_lanceues.memory.ConversationSession;
import dev.romulus_lanceues.memory.ConversationTurn;
import dev.romulus_lanceues.util.RateLimiter;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Mentions;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageReference;
import net.dv8tion.jda.api.entities.MessageType;
import net.dv8tion.jda.api.entities.SelfUser;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.entities.channel.unions.ChannelUnion;
import net.dv8tion.jda.api.events.channel.update.ChannelUpdateArchivedEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.requests.RestAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import org.mockito.ArgumentCaptor;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationListenerTest {
    private static final long THREAD_ID = 123L;
    private ConversationListener listener;
    private ConversationManager manager;
    private ConversationSession session;
    private RateLimiter limiter;
    private FakeChatService chat;
    private MessageReceivedEvent event;
    private Message incoming;
    private Clock clock;
    private RestAction<Void> typingAction;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> refreshTask;
    private Runnable refreshTick;
    private final List<String> notifications = new ArrayList<>();
    private final List<String> submitted = new ArrayList<>();
    private final List<CompletableFuture<Message>> sends = new ArrayList<>();
    private final List<MessageCreateAction> actions = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        clock = spy(Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC));
        manager = spy(new ConversationManager(
                clock,
                mock(ScheduledExecutorService.class)));
        session = manager.createSession(THREAD_ID, "Initial instructions", "Alice");
        session.releaseLock(); // Simulate completion of the command's initial response.
        limiter = mock(RateLimiter.class);
        when(limiter.checkCooldown(42L)).thenReturn(Optional.empty());
        chat = new FakeChatService();
        scheduler = mock(ScheduledExecutorService.class);
        refreshTask = mock(ScheduledFuture.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), eq(5L), eq(5L), eq(TimeUnit.SECONDS)))
                .thenAnswer(call -> {
                    refreshTick = call.getArgument(0);
                    return refreshTask;
                });
        listener = new ConversationListener(manager, chat, limiter, scheduler);
        event = mock(MessageReceivedEvent.class);
        incoming = mock(Message.class);
        User author = mock(User.class);
        when(author.getName()).thenReturn("Alice");
        when(author.getIdLong()).thenReturn(42L);
        when(event.getAuthor()).thenReturn(author);
        when(incoming.getAuthor()).thenReturn(author);
        when(event.getMessage()).thenReturn(incoming);
        when(event.getChannelType()).thenReturn(ChannelType.GUILD_PUBLIC_THREAD);
        MessageChannelUnion channel = mock(MessageChannelUnion.class);
        when(channel.getIdLong()).thenReturn(THREAD_ID);
        when(event.getChannel()).thenReturn(channel);
        when(incoming.getChannel()).thenReturn(channel);
        typingAction = mock(RestAction.class);
        when(typingAction.setCheck(any())).thenReturn(typingAction);
        doAnswer(call -> {
            Consumer<Void> success = call.getArgument(0);
            success.accept(null);
            return null;
        }).when(typingAction).queue(any(), any());
        when(channel.sendTyping()).thenReturn(typingAction);
        when(incoming.getContentRaw()).thenReturn("<@99> Give an example");
        JDA jda = mock(JDA.class);
        SelfUser cloud = mock(SelfUser.class);
        when(cloud.getId()).thenReturn("99");
        when(event.getJDA()).thenReturn(jda);
        when(jda.getSelfUser()).thenReturn(cloud);
        Mentions mentions = mock(Mentions.class);
        when(incoming.getMentions()).thenReturn(mentions);
        when(mentions.isMentioned(cloud, Message.MentionType.USER)).thenReturn(true);

        when(incoming.reply(anyString())).thenAnswer(call -> {
            String text = call.getArgument(0);
            MessageCreateAction action = mock(MessageCreateAction.class, RETURNS_SELF);
            actions.add(action);
            when(action.submit()).thenAnswer(send -> {
                int index = submitted.size();
                submitted.add(text);
                return index < sends.size() ? sends.get(index)
                        : CompletableFuture.completedFuture(mock(Message.class));
            });
            doAnswer(notification -> {
                notifications.add(text);
                return null;
            }).when(action).queue(any(), any());
            return action;
        });
    }

    @Test
    void inactiveSessionIsQuietlyRejectedAndReleasesGuard() {
        session.makeSessionInactive();
        listener.onMessageReceived(event);
        assertTrue(notifications.isEmpty());
        assertEquals(0, chat.calls);
        assertTrue(session.getTurns().isEmpty());
        assertReleased();
    }

    @Test
    void unexpectedInsertionFailureHasItsOwnMessage() {
        doThrow(new IllegalArgumentException("Insertion failed"))
                .when(manager).insertSessionTurn(eq(THREAD_ID), any());
        listener.onMessageReceived(event);
        assertEquals(List.of(ConversationListener.INSERT_FAILED), notifications);
        assertEquals(0, chat.calls);
        assertReleased();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void generationFailuresAreReportedOnceAndReleaseGuard(boolean synchronous) {
        if (synchronous) chat.startFailure = new IllegalStateException("Service unavailable");
        else chat.response = CompletableFuture.failedFuture(new IllegalStateException("Service unavailable"));
        listener.onMessageReceived(event);
        assertEquals(List.of(ConversationListener.GENERATION_FAILED), notifications);
        assertTrue(submitted.isEmpty());
        assertEquals(1, session.getTurns().size()); // Only the accepted human turn.
        assertReleased();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void emptyGenerationIsReportedOnlyOnce(String result) {
        chat.response = CompletableFuture.completedFuture(result);
        listener.onMessageReceived(event);
        assertEquals(List.of(ConversationListener.GENERATION_FAILED), notifications);
        assertTrue(submitted.isEmpty());
        assertReleased();
    }

    @Test
    void guardStaysHeldThroughGenerationDeliveryAndRecording() {
        CompletableFuture<String> generated = new CompletableFuture<>();
        chat.response = generated;
        sends.add(new CompletableFuture<>());
        listener.onMessageReceived(event);
        assertFalse(session.tryAcquireLock());
        assertTrue(submitted.isEmpty());
        generated.complete("Answer");
        assertEquals(List.of("Answer"), submitted);
        assertFalse(session.tryAcquireLock());
        assertEquals(1, session.getTurns().size());
        sends.getFirst().complete(mock(Message.class));
        assertEquals(2, session.getTurns().size());
        assertEquals("Answer", session.getTurns().getLast().content());
        assertTrue(session.getTurns().getLast().isBot());
        assertTrue(notifications.isEmpty());
        assertReleased();
    }

    @Test
    void chunkFailureReportsDeliveryAndSkipsLaterSendsAndRecording() {
        chat.response = CompletableFuture.completedFuture("a".repeat(4010));
        sends.add(new CompletableFuture<>());
        sends.add(new CompletableFuture<>());
        listener.onMessageReceived(event);
        assertEquals(1, submitted.size());
        sends.get(0).complete(mock(Message.class));
        assertEquals(2, submitted.size());
        sends.get(1).completeExceptionally(new IllegalStateException("Discord unavailable"));
        assertEquals(2, submitted.size());
        assertEquals(List.of(ConversationListener.DELIVERY_FAILED), notifications);
        assertEquals(1, session.getTurns().size());
        assertReleased();
    }

    @Test
    void synchronousSendSubmissionFailureIsClassifiedAsDeliveryFailure() {
        when(incoming.reply("Answer")).thenThrow(new IllegalStateException("Cannot submit"));
        listener.onMessageReceived(event);
        assertEquals(List.of(ConversationListener.DELIVERY_FAILED), notifications);
        assertEquals(1, session.getTurns().size());
        assertReleased();
    }

    @Test
    void retirementDuringDeliverySkipsRecordingAndNotifications() {
        sends.add(new CompletableFuture<>());
        listener.onMessageReceived(event);
        session.makeSessionInactive();
        sends.getFirst().complete(mock(Message.class));
        assertEquals(List.of("Answer"), submitted);
        assertTrue(notifications.isEmpty());
        assertEquals(1, session.getTurns().size());
        assertReleased();
    }

    @Test
    void notificationSubmissionFailureStillReleasesGuard() {
        chat.response = CompletableFuture.failedFuture(new IllegalStateException("AI unavailable"));
        when(incoming.reply(ConversationListener.GENERATION_FAILED))
                .thenThrow(new IllegalStateException("Cannot notify"));
        assertDoesNotThrow(() -> listener.onMessageReceived(event));
        assertReleased();
    }

    @Test
    void busyRequestDoesNotReleaseAnotherRequestsGuard() {
        assertTrue(session.tryAcquireLock());
        listener.onMessageReceived(event);
        assertEquals(1, notifications.size());
        assertEquals(0, chat.calls);
        assertFalse(session.tryAcquireLock());
        session.releaseLock();
    }

    @Test
    void cooldownDoesNotLeaveSessionBusy() {
        when(limiter.checkCooldown(42L)).thenReturn(Optional.of(Duration.ofSeconds(2)));
        listener.onMessageReceived(event);
        assertEquals(List.of("Please wait 2 seconds before asking another question."), notifications);
        assertEquals(0, chat.calls);
        assertReleased();
    }

    @Test
    void repliesDisableMentions() {
        listener.onMessageReceived(event);
        for (MessageCreateAction action : actions) {
            verify(action).mentionRepliedUser(false);
            verify(action).setAllowedMentions(Collections.emptyList());
        }
    }

    @Test
    void initialResponseReservationRejectsMentionsWithoutReleasingOwnersGuard() {
        ConversationSession initializing = manager.createSession(456L, "New thread", "Alice");
        when(event.getChannel().getIdLong()).thenReturn(456L);
        listener.onMessageReceived(event);
        assertEquals(List.of("I'm already preparing a response. Please try again after I finish."), notifications);
        assertEquals(0, chat.calls);
        assertTrue(initializing.getTurns().isEmpty());
        assertFalse(initializing.tryAcquireLock());

        initializing.releaseLock(); // The initial response finishes; mentions may now proceed.
        listener.onMessageReceived(event);
        assertEquals(1, chat.calls);
        assertEquals(2, initializing.getTurns().size());
        assertTrue(initializing.tryAcquireLock());
        initializing.releaseLock();
    }

    @ParameterizedTest
    @ValueSource(strings = {"<@99> Give an example", "<@!99> Give an example"})
    void storesAndGeneratesFromCleanedMentionText(String raw) {
        when(incoming.getContentRaw()).thenReturn(raw);
        listener.onMessageReceived(event);
        assertEquals("Give an example", session.getTurns().getFirst().content());
        assertEquals("Give an example", chat.history.getLast().content());
        assertReleased();
    }

    @ParameterizedTest
    @ValueSource(strings = {"<@99>", "  <@!99>  "})
    void mentionWithoutQuestionDoesNotConsumeCooldownOrAcquireGuard(String raw) {
        when(incoming.getContentRaw()).thenReturn(raw);
        listener.onMessageReceived(event);
        assertEquals(List.of("Hello, Alice! What would you like to ask?"), notifications);
        assertEquals(0, chat.calls);
        verifyNoInteractions(limiter);
        assertTrue(session.getTurns().isEmpty());
        assertReleased();
    }

    @Test
    void passiveMessageIsRecordedOnceWithAuthorAndTextWithoutResponding() {
        preparePassiveMessage("  Hello <@1234>\nHow are you?  ");

        listener.onMessageReceived(event);

        assertEquals(1, session.getTurns().size());
        ConversationTurn turn = session.getTurns().getFirst();
        assertEquals("Alice", turn.speaker());
        assertEquals("Hello <@1234>\nHow are you?", turn.content());
        assertFalse(turn.isBot());
        verify(manager, times(1)).insertSessionTurn(eq(THREAD_ID), any());
        assertPassiveSilence();
        assertReleased();
    }

    @Test
    void nextAddressedRequestIncludesPreviouslyRecordedChatter() {
        preparePassiveMessage("We chose the blue design");
        listener.onMessageReceived(event);
        when(incoming.getMentions().isMentioned(event.getJDA().getSelfUser(), Message.MentionType.USER))
                .thenReturn(true);
        when(incoming.getContentRaw()).thenReturn("<@99> What did we choose?");

        listener.onMessageReceived(event);

        assertEquals(1, chat.calls);
        assertEquals(List.of("Initial instructions", "We chose the blue design", "What did we choose?"),
                chat.history.stream().map(ConversationTurn::content).toList());
        assertEquals(3, session.getTurns().size()); // Chatter, question, and answer.
        assertReleased();
    }

    @Test
    void passiveMessageIsRecordedDuringGenerationWithoutReleasingItsGuard() {
        CompletableFuture<String> generated = new CompletableFuture<>();
        chat.response = generated;
        listener.onMessageReceived(event);
        List<ConversationTurn> requestSnapshot = chat.history;
        preparePassiveMessage("Additional context");

        listener.onMessageReceived(event);

        assertEquals(1, chat.calls);
        assertEquals(2, session.getTurns().size());
        assertEquals("Additional context", session.getTurns().getLast().content());
        assertEquals(2, requestSnapshot.size()); // Pinned prompt and original question only.
        assertFalse(session.tryAcquireLock());
        verify(limiter, times(1)).checkCooldown(42L);
        assertTrue(notifications.isEmpty());
        assertTrue(submitted.isEmpty());

        generated.complete("Answer");
        assertEquals(3, session.getTurns().size());
        assertReleased();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\n\t"})
    void blankPassiveTextIsSkipped(String text) {
        preparePassiveMessage(text);
        listener.onMessageReceived(event);
        assertTrue(session.getTurns().isEmpty());
        verify(manager, never()).insertSessionTurn(anyLong(), any());
        assertPassiveSilence();
    }

    @Test
    void expiredPassiveMessageIsQuietlyRejectedWithoutRevivingSession() {
        preparePassiveMessage("Late message");
        when(clock.instant()).thenReturn(Instant.parse("2026-01-01T12:45:01Z"));

        listener.onMessageReceived(event);

        assertTrue(session.getTurns().isEmpty());
        assertEquals(Instant.parse("2026-01-01T12:00:00Z"), session.getLastActivity());
        assertFalse(session.verifyStateEligibility(ConversationManager.TIME_TO_LIVE_DURATION));
        assertPassiveSilence();
    }

    @Test
    void inactivePassiveMessageIsQuietlyRejected() {
        preparePassiveMessage("Late message");
        session.makeSessionInactive();
        assertDoesNotThrow(() -> listener.onMessageReceived(event));
        assertTrue(session.getTurns().isEmpty());
        assertPassiveSilence();
    }

    @Test
    void sessionRemovalDuringPassiveInsertionIsQuietlyHandled() {
        preparePassiveMessage("Late message");
        doAnswer(call -> {
            manager.removeSession(THREAD_ID);
            return call.callRealMethod();
        }).when(manager).insertSessionTurn(eq(THREAD_ID), any());

        assertDoesNotThrow(() -> listener.onMessageReceived(event));

        assertTrue(session.getTurns().isEmpty());
        assertTrue(manager.getSession(THREAD_ID).isEmpty());
        assertPassiveSilence();
    }

    @Test
    void unexpectedPassiveInsertionFailureDoesNotReplyOrEscapeListener() {
        preparePassiveMessage("Hello");
        doThrow(new IllegalArgumentException("Insertion failed"))
                .when(manager).insertSessionTurn(eq(THREAD_ID), any());
        assertDoesNotThrow(() -> listener.onMessageReceived(event));
        assertTrue(session.getTurns().isEmpty());
        assertPassiveSilence();
        assertReleased();
    }

    @ParameterizedTest
    @ValueSource(strings = {"bot", "non-thread", "unmanaged"})
    void passiveMessagesOutsideManagedHumanThreadsAreIgnored(String reason) {
        preparePassiveMessage("Hello");
        switch (reason) {
            case "bot" -> when(event.getAuthor().isBot()).thenReturn(true);
            case "non-thread" -> when(event.getChannelType()).thenReturn(ChannelType.TEXT);
            case "unmanaged" -> when(event.getChannel().getIdLong()).thenReturn(456L);
        }
        listener.onMessageReceived(event);
        assertTrue(session.getTurns().isEmpty());
        verify(manager, never()).insertSessionTurn(anyLong(), any());
        assertPassiveSilence();
    }

    @Test
    void cachedReplyToCloudWithoutMentionUsesAddressedWorkflowOnce() {
        preparePassiveMessage("Can you explain that?");
        when(incoming.getType()).thenReturn(MessageType.INLINE_REPLY);
        doReturn(messageBy("99")).when(incoming).getReferencedMessage();

        listener.onMessageReceived(event);

        assertEquals(1, chat.calls);
        assertEquals("Can you explain that?", chat.history.getLast().content());
        assertEquals("Alice", chat.history.getLast().speaker());
        assertEquals(2, session.getTurns().size());
        verify(incoming, never()).getMessageReference();
        assertReleased();
    }

    @Test
    void cachedReplyToAnotherAuthorIsRecordedPassively() {
        preparePassiveMessage("I agree");
        when(incoming.getType()).thenReturn(MessageType.INLINE_REPLY);
        doReturn(messageBy("1234")).when(incoming).getReferencedMessage();
        listener.onMessageReceived(event);
        assertEquals(1, session.getTurns().size());
        assertEquals("I agree", session.getTurns().getFirst().content());
        assertPassiveSilence();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mentionAndReplyTriggerOnlyOneRequestWithoutRetrieval(boolean cached) {
        when(incoming.getType()).thenReturn(MessageType.INLINE_REPLY);
        if (cached) doReturn(messageBy("99")).when(incoming).getReferencedMessage();
        else when(incoming.getMessageReference()).thenReturn(mock(MessageReference.class));

        listener.onMessageReceived(event);

        assertEquals(1, chat.calls);
        assertEquals(2, session.getTurns().size());
        verify(limiter, times(1)).checkCooldown(42L);
        verify(incoming, never()).getReferencedMessage();
        verify(incoming, never()).getMessageReference();
        assertReleased();
    }

    @ParameterizedTest
    @ValueSource(strings = {"99", "1234"})
    void missingReplyTargetIsRetrievedBeforeRoutingOrInsertion(String authorId) {
        CompletableFuture<Message> retrieval = prepareReplyRetrieval();

        listener.onMessageReceived(event);

        assertTrue(session.getTurns().isEmpty());
        assertEquals(0, chat.calls);
        verifyNoInteractions(limiter);
        assertReleased(); // Retrieval does not reserve generation.

        retrieval.complete(messageBy(authorId));

        if (authorId.equals("99")) {
            assertEquals(1, chat.calls);
            assertEquals(2, session.getTurns().size());
            assertEquals("Follow-up question", chat.history.getLast().content());
            assertReleased();
        } else {
            assertEquals(1, session.getTurns().size());
            assertPassiveSilence();
        }
        verify(manager, times(1)).insertSessionTurn(eq(THREAD_ID), argThat(turn -> !turn.isBot()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inaccessibleReplyTargetFallsBackToPassiveRecording(boolean synchronous) {
        CompletableFuture<Message> retrieval = prepareReplyRetrieval();
        if (synchronous) {
            when(incoming.getMessageReference().resolve())
                    .thenThrow(new IllegalStateException("Missing access"));
        }

        assertDoesNotThrow(() -> listener.onMessageReceived(event));
        if (!synchronous) retrieval.completeExceptionally(new IllegalStateException("Unknown message"));

        assertEquals(1, session.getTurns().size());
        assertEquals("Follow-up question", session.getTurns().getFirst().content());
        verify(manager, times(1)).insertSessionTurn(eq(THREAD_ID), any());
        assertPassiveSilence();
        assertReleased();
    }

    @Test
    void replyWithoutReferenceMetadataIsRecordedPassively() {
        preparePassiveMessage("Follow-up question");
        when(incoming.getType()).thenReturn(MessageType.INLINE_REPLY);
        listener.onMessageReceived(event);
        assertEquals(1, session.getTurns().size());
        assertPassiveSilence();
    }

    @Test
    void threadStarterReferenceDoesNotTriggerReplyHandling() {
        preparePassiveMessage("Thread text");
        when(incoming.getType()).thenReturn(MessageType.THREAD_STARTER_MESSAGE);
        doReturn(messageBy("99")).when(incoming).getReferencedMessage();
        listener.onMessageReceived(event);
        assertEquals(1, session.getTurns().size());
        verify(incoming, never()).getReferencedMessage();
        assertPassiveSilence();
    }

    @ParameterizedTest
    @ValueSource(strings = {"busy", "cooldown", "expired"})
    void retrievedReplyRechecksAddressedWorkflowConditions(String condition) {
        CompletableFuture<Message> retrieval = prepareReplyRetrieval();
        listener.onMessageReceived(event);
        switch (condition) {
            case "busy" -> assertTrue(session.tryAcquireLock());
            case "cooldown" -> when(limiter.checkCooldown(42L)).thenReturn(Optional.of(Duration.ofSeconds(2)));
            case "expired" -> when(clock.instant()).thenReturn(Instant.parse("2026-01-01T12:45:01Z"));
        }

        retrieval.complete(messageBy("99"));

        assertEquals(0, chat.calls);
        assertTrue(session.getTurns().isEmpty());
        assertEquals(condition.equals("expired") ? 0 : 1, notifications.size());
        if (condition.equals("busy")) {
            assertEquals("I'm already preparing a response. Please try again after I finish.", notifications.getFirst());
            assertFalse(session.tryAcquireLock());
            session.releaseLock();
        } else {
            if (!condition.equals("expired")) {
                assertEquals("Please wait 2 seconds before asking another question.", notifications.getFirst());
            }
            assertReleased();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retrievalCannotInsertIntoReplacementSession(boolean failedRetrieval) {
        CompletableFuture<Message> retrieval = prepareReplyRetrieval();
        listener.onMessageReceived(event);
        manager.removeSession(THREAD_ID);
        ConversationSession replacement = manager.createSession(THREAD_ID, "New instructions", "Bob");
        replacement.releaseLock();

        if (failedRetrieval) retrieval.completeExceptionally(new IllegalStateException("Unknown message"));
        else retrieval.complete(messageBy("99"));

        assertTrue(session.getTurns().isEmpty());
        assertTrue(replacement.getTurns().isEmpty());
        assertPassiveSilence();
        assertTrue(replacement.tryAcquireLock());
        replacement.releaseLock();
    }

    @Test
    void blankDirectReplyUsesExistingQuestionPromptWithoutCooldown() {
        preparePassiveMessage("  ");
        when(incoming.getType()).thenReturn(MessageType.INLINE_REPLY);
        doReturn(messageBy("99")).when(incoming).getReferencedMessage();
        listener.onMessageReceived(event);
        assertEquals(List.of("Hello, Alice! What would you like to ask?"), notifications);
        assertTrue(session.getTurns().isEmpty());
        assertEquals(0, chat.calls);
        verifyNoInteractions(limiter);
        assertReleased();
    }

    @Test
    void archivingRetiresAndRemovesOnlyTheMatchingSession() {
        ConversationSession other = manager.createSession(456L, "Other conversation", "Bob");

        listener.onChannelUpdateArchived(archiveEvent(THREAD_ID, true));

        assertTrue(manager.getSession(THREAD_ID).isEmpty());
        assertFalse(session.verifyStateEligibility(ConversationManager.TIME_TO_LIVE_DURATION));
        assertSame(other, manager.getSession(456L).orElseThrow());
        assertTrue(other.verifyStateEligibility(ConversationManager.TIME_TO_LIVE_DURATION));
        assertThrows(IllegalStateException.class,
                () -> manager.insertSessionTurn(THREAD_ID, ConversationTurn.user("Alice", "Late message")));
        assertPassiveSilence();
    }

    @Test
    void unarchivingDoesNotRemoveAnExistingSession() {
        listener.onChannelUpdateArchived(archiveEvent(THREAD_ID, false));

        assertSame(session, manager.getSession(THREAD_ID).orElseThrow());
        assertTrue(session.verifyStateEligibility(ConversationManager.TIME_TO_LIVE_DURATION));
        verify(manager, never()).removeSession(anyLong());
        assertPassiveSilence();
    }

    @Test
    void repeatedArchivingAndUnarchivingDoNotRecreateRetiredContext() {
        listener.onChannelUpdateArchived(archiveEvent(THREAD_ID, true));
        assertDoesNotThrow(() -> listener.onChannelUpdateArchived(archiveEvent(THREAD_ID, true)));
        listener.onChannelUpdateArchived(archiveEvent(THREAD_ID, false));
        listener.onMessageReceived(event);

        assertTrue(manager.getSession(THREAD_ID).isEmpty());
        assertFalse(session.verifyStateEligibility(ConversationManager.TIME_TO_LIVE_DURATION));
        assertPassiveSilence();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void archiveUpdatesForUnmanagedThreadsAreHarmless(boolean archived) {
        assertDoesNotThrow(() -> listener.onChannelUpdateArchived(archiveEvent(456L, archived)));
        assertTrue(manager.getSession(456L).isEmpty());
        assertSame(session, manager.getSession(THREAD_ID).orElseThrow());
        assertTrue(session.verifyStateEligibility(ConversationManager.TIME_TO_LIVE_DURATION));
        assertPassiveSilence();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replyLookupCompletingAfterArchiveCannotRestoreContext(boolean failedRetrieval) {
        CompletableFuture<Message> retrieval = prepareReplyRetrieval();
        listener.onMessageReceived(event);
        listener.onChannelUpdateArchived(archiveEvent(THREAD_ID, true));

        if (failedRetrieval) retrieval.completeExceptionally(new IllegalStateException("Unknown message"));
        else retrieval.complete(messageBy("99"));

        assertTrue(manager.getSession(THREAD_ID).isEmpty());
        assertTrue(session.getTurns().isEmpty());
        assertFalse(session.verifyStateEligibility(ConversationManager.TIME_TO_LIVE_DURATION));
        verify(manager, never()).insertSessionTurn(anyLong(), any());
        assertPassiveSilence();
    }

    @ParameterizedTest
    @ValueSource(strings = {"mention", "cached-reply", "retrieved-reply"})
    void acceptedRequestsQueueTypingBeforeGenerationWithoutWaitingForItsCompletion(String trigger) {
        CompletableFuture<String> generated = new CompletableFuture<>();
        chat.response = generated;
        CompletableFuture<Message> retrieval = null;
        if (trigger.equals("cached-reply")) {
            preparePassiveMessage("Follow-up question");
            when(incoming.getType()).thenReturn(MessageType.INLINE_REPLY);
            doReturn(messageBy("99")).when(incoming).getReferencedMessage();
        } else if (trigger.equals("retrieved-reply")) {
            retrieval = prepareReplyRetrieval();
        }
        doAnswer(call -> {
            assertEquals(0, chat.calls);
            assertEquals(1, session.getTurns().size());
            assertFalse(session.tryAcquireLock());
            // Leave typing callbacks pending; generation must still start.
            return null;
        }).when(typingAction).queue(any(), any());

        listener.onMessageReceived(event);
        if (retrieval != null) {
            verify(incoming.getChannel(), never()).sendTyping();
            retrieval.complete(messageBy("99"));
        }

        verify(incoming.getChannel(), times(1)).sendTyping();
        verify(typingAction, times(1)).queue(any(), any());
        assertEquals(1, chat.calls);
        assertFalse(session.tryAcquireLock());
        assertTrue(notifications.isEmpty());
        generated.complete("Answer");
        assertEquals(List.of("Answer"), submitted);
        assertReleased();
    }

    @ParameterizedTest
    @ValueSource(strings = {"creation", "submission", "callback"})
    void typingFailureDoesNotInterruptGenerationOrReleaseItsGuard(String stage) {
        CompletableFuture<String> generated = new CompletableFuture<>();
        chat.response = generated;
        AtomicReference<Consumer<Throwable>> failureCallback = new AtomicReference<>();
        RuntimeException failure = new IllegalStateException("Typing unavailable");
        switch (stage) {
            case "creation" -> when(incoming.getChannel().sendTyping()).thenThrow(failure);
            case "submission" -> doThrow(failure).when(typingAction).queue(any(), any());
            case "callback" -> doAnswer(call -> {
                failureCallback.set(call.getArgument(1));
                return null;
            }).when(typingAction).queue(any(), any());
        }

        assertDoesNotThrow(() -> listener.onMessageReceived(event));
        if (failureCallback.get() != null) failureCallback.get().accept(failure);

        assertEquals(1, chat.calls);
        assertFalse(session.tryAcquireLock());
        assertTrue(notifications.isEmpty());
        generated.complete("Answer");
        assertEquals(List.of("Answer"), submitted);
        assertEquals(2, session.getTurns().size());
        assertTrue(notifications.isEmpty());
        assertReleased();
    }

    @Test
    void lateTypingFailureDoesNotReleaseAnotherRequestsGuard() {
        AtomicReference<Consumer<Throwable>> failureCallback = new AtomicReference<>();
        doAnswer(call -> {
            failureCallback.set(call.getArgument(1));
            return null;
        }).when(typingAction).queue(any(), any());
        listener.onMessageReceived(event);
        assertReleased();
        assertTrue(session.tryAcquireLock()); // A later request owns the guard now.

        failureCallback.get().accept(new IllegalStateException("Late typing failure"));

        assertFalse(session.tryAcquireLock());
        assertTrue(notifications.isEmpty());
        assertEquals(List.of("Answer"), submitted);
        session.releaseLock();
    }

    @ParameterizedTest
    @ValueSource(strings = {"blank", "busy", "cooldown", "inactive", "insertion-failure"})
    void rejectedRequestsDoNotSendTyping(String reason) {
        switch (reason) {
            case "blank" -> when(incoming.getContentRaw()).thenReturn("<@99>");
            case "busy" -> assertTrue(session.tryAcquireLock());
            case "cooldown" -> when(limiter.checkCooldown(42L)).thenReturn(Optional.of(Duration.ofSeconds(2)));
            case "inactive" -> session.makeSessionInactive();
            case "insertion-failure" -> doThrow(new IllegalArgumentException("Insertion failed"))
                    .when(manager).insertSessionTurn(eq(THREAD_ID), any());
        }

        listener.onMessageReceived(event);

        verify(incoming.getChannel(), never()).sendTyping();
        verifyNoInteractions(typingAction);
        assertEquals(0, chat.calls);
        if (reason.equals("busy")) session.releaseLock();
        assertReleased();
    }

    @ParameterizedTest
    @ValueSource(strings = {"answer", "generation-failure", "empty-answer", "expired", "replacement"})
    void retiredWorkflowDoesNotSendLateAnswersOrErrors(String outcome) {
        CompletableFuture<String> generated = new CompletableFuture<>();
        chat.response = generated;
        listener.onMessageReceived(event);
        ConversationSession replacement = null;
        if (outcome.equals("expired")) {
            when(clock.instant()).thenReturn(Instant.parse("2026-01-01T12:45:01Z"));
        } else {
            listener.onChannelUpdateArchived(archiveEvent(THREAD_ID, true));
            if (outcome.equals("replacement")) {
                replacement = manager.createSession(THREAD_ID, "New instructions", "Bob");
            }
        }

        if (outcome.equals("generation-failure")) {
            generated.completeExceptionally(new IllegalStateException("Generation failed"));
        } else {
            generated.complete(outcome.equals("empty-answer") ? "" : "Late answer");
        }

        assertTrue(submitted.isEmpty());
        assertTrue(notifications.isEmpty());
        verify(incoming, never()).reply(anyString());
        assertEquals(1, session.getTurns().size());
        assertReleased();
        if (replacement != null) {
            assertTrue(replacement.getTurns().isEmpty());
            assertFalse(replacement.tryAcquireLock());
            replacement.releaseLock();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void archiveBetweenChunksStopsRemainingDeliveryAndFailureNotifications(boolean sendFails) {
        chat.response = CompletableFuture.completedFuture("a".repeat(4010));
        sends.add(new CompletableFuture<>());
        listener.onMessageReceived(event);
        assertEquals(1, submitted.size());
        listener.onChannelUpdateArchived(archiveEvent(THREAD_ID, true));

        if (sendFails) sends.getFirst().completeExceptionally(new IllegalStateException("Send failed"));
        else sends.getFirst().complete(mock(Message.class));

        assertEquals(1, submitted.size());
        assertTrue(notifications.isEmpty());
        assertEquals(1, session.getTurns().size());
        assertTrue(manager.getSession(THREAD_ID).isEmpty());
        assertReleased();
    }

    @Test
    void queuedResponseAndTypingChecksRejectRetirementAndReleaseGuardOnCancellation() {
        sends.add(new CompletableFuture<>());
        listener.onMessageReceived(event);
        ArgumentCaptor<BooleanSupplier> responseCheck = ArgumentCaptor.forClass(BooleanSupplier.class);
        ArgumentCaptor<BooleanSupplier> typingCheck = ArgumentCaptor.forClass(BooleanSupplier.class);
        verify(actions.getFirst()).setCheck(responseCheck.capture());
        verify(typingAction).setCheck(typingCheck.capture());
        assertTrue(responseCheck.getValue().getAsBoolean());
        assertTrue(typingCheck.getValue().getAsBoolean());

        listener.onChannelUpdateArchived(archiveEvent(THREAD_ID, true));

        assertFalse(responseCheck.getValue().getAsBoolean());
        assertFalse(typingCheck.getValue().getAsBoolean());
        // JDA completes the request exceptionally when its dispatch check rejects it.
        sends.getFirst().completeExceptionally(new CancellationException("Session retired"));
        assertTrue(notifications.isEmpty());
        assertEquals(1, session.getTurns().size());
        assertReleased();
    }

    @Test
    void queuedNotificationCheckRejectsArchiveWithoutReleasingOwnersGuard() {
        assertTrue(session.tryAcquireLock());
        listener.onMessageReceived(event);
        ArgumentCaptor<BooleanSupplier> check = ArgumentCaptor.forClass(BooleanSupplier.class);
        verify(actions.getFirst()).setCheck(check.capture());
        assertTrue(check.getValue().getAsBoolean());

        listener.onChannelUpdateArchived(archiveEvent(THREAD_ID, true));

        assertFalse(check.getValue().getAsBoolean());
        assertFalse(session.tryAcquireLock());
        session.releaseLock();
    }

    @Test
    void unexpectedRecordingFailureForActiveSessionStillNotifiesAndReleasesGuard() {
        doAnswer(call -> {
            ConversationTurn turn = call.getArgument(1);
            if (turn.isBot()) throw new IllegalArgumentException("Recording failed");
            return call.callRealMethod();
        }).when(manager).insertSessionTurn(eq(THREAD_ID), any());

        listener.onMessageReceived(event);

        assertEquals(List.of("Answer"), submitted);
        assertEquals(List.of(ConversationListener.RECORDING_FAILED), notifications);
        assertReleased();
    }

    @Test
    void typingRefreshesThroughGenerationAndDeliveryThenStops() {
        CompletableFuture<String> generated = new CompletableFuture<>();
        chat.response = generated;
        sends.add(new CompletableFuture<>());
        listener.onMessageReceived(event);
        assertNotNull(refreshTick);
        verify(incoming.getChannel(), times(1)).sendTyping();

        refreshTick.run();
        refreshTick.run();
        verify(incoming.getChannel(), times(3)).sendTyping();
        generated.complete("Answer");
        refreshTick.run(); // Delivery is still pending.
        verify(incoming.getChannel(), times(4)).sendTyping();
        verify(refreshTask, never()).cancel(anyBoolean());

        sends.getFirst().complete(mock(Message.class));
        verify(refreshTask).cancel(false);
        refreshTick.run(); // Simulate an already queued timer callback after cancellation.
        verify(incoming.getChannel(), times(4)).sendTyping();
        assertReleased();
    }

    @ParameterizedTest
    @ValueSource(strings = {"synchronous-generation", "asynchronous-generation", "empty-answer", "delivery", "recording"})
    void responseFailuresCancelTypingRefresh(String stage) {
        RuntimeException failure = new IllegalStateException("Response failed");
        switch (stage) {
            case "synchronous-generation" -> chat.startFailure = failure;
            case "asynchronous-generation" -> chat.response = CompletableFuture.failedFuture(failure);
            case "empty-answer" -> chat.response = CompletableFuture.completedFuture("");
            case "delivery" -> sends.add(CompletableFuture.failedFuture(failure));
            case "recording" -> doAnswer(call -> {
                if (((ConversationTurn) call.getArgument(1)).isBot()) throw failure;
                return call.callRealMethod();
            }).when(manager).insertSessionTurn(eq(THREAD_ID), any());
        }

        listener.onMessageReceived(event);

        verify(refreshTask).cancel(false);
        refreshTick.run();
        verify(incoming.getChannel(), times(1)).sendTyping();
        assertReleased();
    }

    @ParameterizedTest
    @ValueSource(strings = {"archive", "expiration", "replacement", "inactive"})
    void sessionRetirementStopsTypingRefreshBeforeSendingAnotherIndicator(String reason) {
        CompletableFuture<String> generated = new CompletableFuture<>();
        chat.response = generated;
        listener.onMessageReceived(event);
        switch (reason) {
            case "archive" -> listener.onChannelUpdateArchived(archiveEvent(THREAD_ID, true));
            case "expiration" -> when(clock.instant()).thenReturn(Instant.parse("2026-01-01T12:45:01Z"));
            case "replacement" -> {
                manager.removeSession(THREAD_ID);
                manager.createSession(THREAD_ID, "New conversation", "Bob");
            }
            case "inactive" -> session.makeSessionInactive();
        }

        refreshTick.run();
        refreshTick.run();

        verify(refreshTask, times(1)).cancel(false);
        verify(incoming.getChannel(), times(1)).sendTyping();
        generated.complete("Late answer");
        verify(refreshTask, times(1)).cancel(false);
        assertTrue(submitted.isEmpty());
        assertTrue(notifications.isEmpty());
        assertReleased();
    }

    @Test
    void queuedTypingCannotDispatchAfterWorkflowCompletion() {
        CompletableFuture<String> generated = new CompletableFuture<>();
        chat.response = generated;
        listener.onMessageReceived(event);
        ArgumentCaptor<BooleanSupplier> check = ArgumentCaptor.forClass(BooleanSupplier.class);
        verify(typingAction).setCheck(check.capture());
        assertTrue(check.getValue().getAsBoolean());

        generated.complete("Answer");

        assertFalse(check.getValue().getAsBoolean());
        verify(refreshTask).cancel(false);
        assertReleased();
    }

    @Test
    void schedulingRejectionDoesNotInterruptResponseOrLeaveGuardHeld() {
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any()))
                .thenThrow(new java.util.concurrent.RejectedExecutionException("Scheduler shut down"));
        listener.onMessageReceived(event);
        assertEquals(List.of("Answer"), submitted);
        assertTrue(notifications.isEmpty());
        assertReleased();
    }

    @Test
    void transientTypingFailureIsRetriedOnNextRefresh() {
        CompletableFuture<String> generated = new CompletableFuture<>();
        chat.response = generated;
        when(incoming.getChannel().sendTyping())
                .thenThrow(new IllegalStateException("Temporary typing failure"))
                .thenReturn(typingAction);
        listener.onMessageReceived(event);

        refreshTick.run();

        verify(incoming.getChannel(), times(2)).sendTyping();
        verify(typingAction).queue(any(), any());
        assertEquals(1, chat.calls);
        assertTrue(notifications.isEmpty());
        generated.complete("Answer");
        verify(refreshTask).cancel(false);
        assertReleased();
    }

    @Test
    void slowTypingRequestDoesNotAccumulateRefreshRequests() {
        AtomicReference<Consumer<Void>> completed = new AtomicReference<>();
        doAnswer(call -> {
            completed.set(call.getArgument(0));
            return null;
        }).when(typingAction).queue(any(), any());
        CompletableFuture<String> generated = new CompletableFuture<>();
        chat.response = generated;
        listener.onMessageReceived(event);

        refreshTick.run();
        refreshTick.run();
        verify(incoming.getChannel(), times(1)).sendTyping();
        completed.get().accept(null);
        refreshTick.run();
        verify(incoming.getChannel(), times(2)).sendTyping();
        generated.complete("Answer");
        verify(refreshTask).cancel(false);
        assertReleased();
    }

    private ChannelUpdateArchivedEvent archiveEvent(long threadId, boolean archived) {
        ChannelUnion thread = mock(ChannelUnion.class);
        when(thread.getIdLong()).thenReturn(threadId);
        return new ChannelUpdateArchivedEvent(event.getJDA(), 1L, thread, !archived, archived);
    }

    private Message messageBy(String authorId) {
        Message message = mock(Message.class);
        User author = mock(User.class);
        when(author.getId()).thenReturn(authorId);
        when(message.getAuthor()).thenReturn(author);
        return message;
    }

    private CompletableFuture<Message> prepareReplyRetrieval() {
        preparePassiveMessage("Follow-up question");
        when(incoming.getType()).thenReturn(MessageType.INLINE_REPLY);
        MessageReference reference = mock(MessageReference.class);
        @SuppressWarnings("unchecked")
        RestAction<Message> action = mock(RestAction.class);
        CompletableFuture<Message> retrieval = new CompletableFuture<>();
        when(incoming.getMessageReference()).thenReturn(reference);
        when(reference.resolve()).thenReturn(action);
        when(action.submit()).thenReturn(retrieval);
        return retrieval;
    }

    private void preparePassiveMessage(String text) {
        when(incoming.getMentions().isMentioned(event.getJDA().getSelfUser(), Message.MentionType.USER))
                .thenReturn(false);
        when(incoming.getContentRaw()).thenReturn(text);
    }

    private void assertPassiveSilence() {
        assertEquals(0, chat.calls);
        verifyNoInteractions(limiter);
        verify(incoming, never()).reply(anyString());
        verify(incoming.getChannel(), never()).sendTyping();
        verifyNoInteractions(scheduler);
        assertTrue(notifications.isEmpty());
        assertTrue(submitted.isEmpty());
    }

    private void assertReleased() {
        assertTrue(session.tryAcquireLock(), "Request must release its generation guard");
        session.releaseLock();
    }

    private static class FakeChatService implements ChatService {
        private CompletableFuture<String> response = CompletableFuture.completedFuture("Answer");
        private RuntimeException startFailure;
        private int calls;
        private List<ConversationTurn> history;
        @Override
        public CompletableFuture<String> ask(String prompt) {
            throw new AssertionError("Listener must use reply");
        }
        @Override
        public CompletableFuture<String> reply(List<ConversationTurn> turns) {
            calls++;
            history = List.copyOf(turns);
            if (startFailure != null) throw startFailure;
            return response;
        }
    }
}
