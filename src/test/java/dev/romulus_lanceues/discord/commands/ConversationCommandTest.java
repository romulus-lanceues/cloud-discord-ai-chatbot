package dev.romulus_lanceues.discord.commands;

import dev.romulus_lanceues.ai.ChatService;
import dev.romulus_lanceues.memory.ConversationManager;
import dev.romulus_lanceues.memory.ConversationSession;
import dev.romulus_lanceues.memory.ConversationTurn;
import dev.romulus_lanceues.util.MessageSplitter;
import dev.romulus_lanceues.util.RateLimiter;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.ThreadChannelAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationCommandTest {
    private static final long USER_ID = 42L;
    private static final long THREAD_ID = 123L;
    private static final String LINK = "<#123>";
    private static final String DELIVERY_ERROR = "An error occurred while sending the response to the user";
    private ConversationCommand command;
    private ConversationManager manager;
    private FakeChatService chat;
    private RateLimiter limiter;
    private SlashCommandInteractionEvent event;
    private ReplyCallbackAction reply;
    private ReplyCallbackAction defer;
    private InteractionHook hook;
    private TextChannel parent;
    private ThreadChannel thread;
    private ThreadChannelAction creation;
    private RestAction<Void> addition;
    private User user;
    private final List<String> submitted = new ArrayList<>();
    private final List<CompletableFuture<Message>> sendResults = new ArrayList<>();
    private final List<MessageCreateAction> sendActions = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC);
        manager = spy(new ConversationManager(clock, mock(ScheduledExecutorService.class)));
        chat = new FakeChatService();
        limiter = mock(RateLimiter.class);
        when(limiter.checkCooldown(USER_ID)).thenReturn(Optional.empty());
        command = new ConversationCommand(limiter, manager, chat);
        event = mock(SlashCommandInteractionEvent.class);
        when(event.getChannelType()).thenReturn(ChannelType.TEXT);
        user = mock(User.class);
        when(user.getIdLong()).thenReturn(USER_ID);
        when(user.getName()).thenReturn("Alice");
        when(event.getUser()).thenReturn(user);
        option("instructions", "  Answer in Spanish  ");
        reply = mock(ReplyCallbackAction.class, RETURNS_SELF);
        when(event.reply(anyString())).thenReturn(reply);
        defer = mock(ReplyCallbackAction.class);
        when(event.deferReply()).thenReturn(defer);
        hook = mock(InteractionHook.class);
        when(hook.editOriginal(anyString())).thenReturn(mock(WebhookMessageEditAction.class, RETURNS_SELF));
        doAnswer(call -> {
            call.<Consumer<InteractionHook>>getArgument(0).accept(hook);
            return null;
        }).when(defer).queue(any());
        MessageChannelUnion channel = mock(MessageChannelUnion.class);
        parent = mock(TextChannel.class);
        when(event.getChannel()).thenReturn(channel);
        when(channel.asTextChannel()).thenReturn(parent);
        creation = mock(ThreadChannelAction.class);
        when(parent.createThreadChannel(anyString())).thenReturn(creation);
        thread = mock(ThreadChannel.class);
        when(thread.getIdLong()).thenReturn(THREAD_ID);
        when(thread.getAsMention()).thenReturn(LINK);
        doAnswer(call -> {
            call.<Consumer<ThreadChannel>>getArgument(0).accept(thread);
            return null;
        }).when(creation).queue(any(), any());
        addition = mock(RestAction.class);
        when(thread.addThreadMember(user)).thenReturn(addition);
        doAnswer(call -> {
            call.<Consumer<Void>>getArgument(0).accept(null);
            return null;
        }).when(addition).queue(any(), any());
        when(thread.sendMessage(anyString())).thenAnswer(call -> {
            String text = call.getArgument(0);
            MessageCreateAction action = mock(MessageCreateAction.class, RETURNS_SELF);
            sendActions.add(action);
            when(action.submit()).thenAnswer(send -> {
                int index = submitted.size();
                submitted.add(text);
                return index < sendResults.size() ? sendResults.get(index)
                        : CompletableFuture.completedFuture(mock(Message.class));
            });
            return action;
        });
    }

    @Test
    void declaresRequiredInstructionsAndOptionalThreadName() {
        assertEquals("conversation", command.name());
        var data = command.commandData();
        assertEquals("conversation", data.getName());
        assertEquals(2, data.getOptions().size());
        var instructions = data.getOptions().get(0);
        assertEquals("instructions", instructions.getName());
        assertEquals(OptionType.STRING, instructions.getType());
        assertTrue(instructions.isRequired());
        var name = data.getOptions().get(1);
        assertEquals("thread_name", name.getName());
        assertEquals(OptionType.STRING, name.getType());
        assertFalse(name.isRequired());
    }

    @Test
    void requiresDependencies() {
        assertThrows(NullPointerException.class, () -> new ConversationCommand(null, manager, chat));
        assertThrows(NullPointerException.class, () -> new ConversationCommand(limiter, null, chat));
        assertThrows(NullPointerException.class, () -> new ConversationCommand(limiter, manager, null));
    }

    @ParameterizedTest
    @EnumSource(value = ChannelType.class, names = {"PRIVATE", "GUILD_PUBLIC_THREAD", "NEWS", "VOICE"})
    void rejectsUnsupportedChannelsBeforeStartingWork(ChannelType type) {
        when(event.getChannelType()).thenReturn(type);
        command.handle(event);
        verify(event).reply("Please start a conversation in a server text channel!");
        assertRejectedBeforeWork();
    }

    @Test
    void rejectsMissingInstructions() {
        when(event.getOption("instructions")).thenReturn(null);
        command.handle(event);
        verify(event).reply("Please provide a non-empty prompt.");
        assertRejectedBeforeWork();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n", "\u2000\u2003\u3000"})
    void rejectsBlankInstructionsWithoutConsumingCooldown(String text) {
        option("instructions", text);
        command.handle(event);
        verify(event).reply("Please provide a non-empty prompt.");
        assertRejectedBeforeWork();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void omittedOrBlankThreadNameUsesDefault(String name) {
        if (name != null) option("thread_name", name);
        command.handle(event);
        verify(parent).createThreadChannel("Cloud Conversation Thread");
    }

    @Test
    void stripsThreadNameAndAcceptsExactlyNinetyCharacters() {
        option("thread_name", "  " + "x".repeat(90) + "  ");
        command.handle(event);
        verify(parent).createThreadChannel("x".repeat(90));
    }

    @Test
    void rejectsLongThreadNameWithoutConsumingCooldown() {
        option("thread_name", "x".repeat(91));
        command.handle(event);
        verify(event).reply("Please provide a thread name within 90 characters.");
        assertRejectedBeforeWork();
    }

    @Test
    void cooldownRejectsBeforeDeferringOrCreatingThread() {
        when(limiter.checkCooldown(USER_ID)).thenReturn(Optional.of(Duration.ofMillis(1501)));
        command.handle(event);
        verify(event).reply(contains("Please wait 2 seconds"));
        verify(reply).setEphemeral(true);
        verify(event, never()).deferReply();
        verifyNoInteractions(parent, manager);
        assertEquals(0, chat.calls);
    }

    @Test
    void waitsForAcknowledgmentBeforeCreatingThread() {
        doNothing().when(defer).queue(any());
        command.handle(event);
        verify(defer).queue(any());
        verifyNoInteractions(parent, manager);
        assertEquals(0, chat.calls);
    }

    @Test
    void registersSessionAndSendsInitialReplyInsideThread() {
        command.handle(event);
        verify(thread).addThreadMember(user);
        verify(hook).editOriginal(contains(LINK));
        verify(event, never()).reply(anyString());
        assertEquals(1, chat.calls);
        assertEquals(1, chat.history.size());
        ConversationTurn initial = chat.history.getFirst();
        assertEquals("Alice", initial.speaker());
        assertEquals("Answer in Spanish", initial.content());
        assertFalse(initial.isBot());
        assertEquals(List.of("Hola"), submitted);
        assertSame(initial, session().getInitialPrompt());
        assertEquals(1, session().getTurns().size());
        assertTrue(session().getTurns().getFirst().isBot());
        assertEquals("Hola", session().getTurns().getFirst().content());
        verify(manager).insertSessionTurn(eq(THREAD_ID), any(ConversationTurn.class));
        verify(hook, never()).editOriginal(DELIVERY_ERROR);
        verify(sendActions.getFirst()).setAllowedMentions(java.util.Collections.emptyList());
        assertGuardReleased();
    }

    @Test
    void pendingGenerationDoesNotSendOrRecordResponse() {
        CompletableFuture<String> generated = new CompletableFuture<>();
        chat.response = generated;
        command.handle(event);
        assertTrue(submitted.isEmpty());
        assertTrue(session().getTurns().isEmpty());
        assertGuardHeld();
        generated.complete("Finished");
        assertEquals(List.of("Finished"), submitted);
        assertEquals("Finished", session().getTurns().getFirst().content());
        assertGuardReleased();
    }

    @Test
    void sendsSequentiallyAndRecordsOnceOnlyAfterFinalAcknowledgment() {
        String result = "a".repeat(2000) + "b".repeat(2000) + "c".repeat(10);
        chat.response = CompletableFuture.completedFuture(result);
        List<String> chunks = MessageSplitter.split(result, ConversationCommand.MAX_PROMPT_PREVIEW_LENGTH);
        for (int i = 0; i < 3; i++) sendResults.add(new CompletableFuture<>());
        command.handle(event);
        assertEquals(List.of(chunks.get(0)), submitted);
        assertTrue(session().getTurns().isEmpty());
        assertGuardHeld();
        sendResults.get(0).complete(mock(Message.class));
        assertEquals(chunks.subList(0, 2), submitted);
        assertTrue(session().getTurns().isEmpty());
        assertGuardHeld();
        sendResults.get(1).complete(mock(Message.class));
        assertEquals(chunks, submitted);
        assertTrue(session().getTurns().isEmpty());
        assertGuardHeld();
        sendResults.get(2).complete(mock(Message.class));
        assertEquals(List.of(String.join("\n", chunks)),
                session().getTurns().stream().map(ConversationTurn::content).toList());
        verify(manager).insertSessionTurn(eq(THREAD_ID), any(ConversationTurn.class));
        assertGuardReleased();
        for (MessageCreateAction action : sendActions) {
            verify(action).setAllowedMentions(java.util.Collections.emptyList());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void failedChunkSkipsLaterSendsAndMemoryUpdate(int failedIndex) {
        chat.response = CompletableFuture.completedFuture("a".repeat(4010));
        for (int i = 0; i < 3; i++) sendResults.add(new CompletableFuture<>());
        command.handle(event);
        for (int i = 0; i < failedIndex; i++) sendResults.get(i).complete(mock(Message.class));
        assertGuardHeld();
        sendResults.get(failedIndex).completeExceptionally(new IllegalStateException("Send failed"));
        assertEquals(failedIndex + 1, submitted.size());
        assertTrue(session().getTurns().isEmpty());
        verify(manager, never()).insertSessionTurn(anyLong(), any());
        verify(hook).editOriginal(DELIVERY_ERROR);
        assertGuardReleased();
    }

    @Test
    void savesOnlyDeliveredTextWhenSplitterTruncatesResponse() {
        String result = "x".repeat(7000);
        chat.response = CompletableFuture.completedFuture(result);
        command.handle(event);
        assertEquals(3, submitted.size());
        assertTrue(submitted.getLast().endsWith(MessageSplitter.TRUNCATION_SUFFIX));
        assertEquals(String.join("\n", submitted), session().getTurns().getFirst().content());
        assertNotEquals(result, session().getTurns().getFirst().content());
    }

    @Test
    void reportsMemoryRecordingFailureAfterDelivery() {
        doThrow(new IllegalStateException("Session inactive"))
                .when(manager).insertSessionTurn(eq(THREAD_ID), any());
        command.handle(event);
        assertEquals(List.of("Hola"), submitted);
        assertTrue(session().getTurns().isEmpty());
        verify(hook).editOriginal(DELIVERY_ERROR);
        assertGuardReleased();
    }

    @Test
    void reportsThreadCreationFailureWithoutStartingConversation() {
        doAnswer(call -> {
            call.<Consumer<Throwable>>getArgument(1).accept(new IllegalStateException("Creation failed"));
            return null;
        }).when(creation).queue(any(), any());
        command.handle(event);
        verify(hook).editOriginal("Opps, something went wrong!");
        verifyNoInteractions(thread, manager);
        assertEquals(0, chat.calls);
    }

    @Test
    void reportsMemberAdditionFailureWithExistingThreadLink() {
        doAnswer(call -> {
            call.<Consumer<Throwable>>getArgument(1).accept(new IllegalStateException("Addition failed"));
            return null;
        }).when(addition).queue(any(), any());
        command.handle(event);
        verify(hook).editOriginal("The thread was created, but I couldn't add you: " + LINK);
        verifyNoInteractions(manager);
        assertEquals(0, chat.calls);
    }

    @Test
    void reportsFailedGenerationWithoutSendingOrRecording() {
        chat.response = CompletableFuture.failedFuture(new IllegalStateException("AI failed"));
        command.handle(event);
        assertGenerationRejected();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void reportsMissingGenerationWithoutSendingOrRecording(String result) {
        chat.response = CompletableFuture.completedFuture(result);
        command.handle(event);
        assertGenerationRejected();
    }

    @Test
    void synchronousGenerationStartupFailureReleasesGuard() {
        chat.startFailure = new IllegalStateException("Could not start generation");
        assertDoesNotThrow(() -> command.handle(event));
        verify(hook).editOriginal(DELIVERY_ERROR);
        assertTrue(submitted.isEmpty());
        assertTrue(session().getTurns().isEmpty());
        assertGuardReleased();
    }

    @Test
    void guardIsHeldWhileRecordingAndReleasedAfterward() {
        doAnswer(call -> {
            assertGuardHeld();
            return call.callRealMethod();
        }).when(manager).insertSessionTurn(eq(THREAD_ID), any(ConversationTurn.class));
        command.handle(event);
        verify(manager).insertSessionTurn(eq(THREAD_ID), any(ConversationTurn.class));
        assertEquals("Hola", session().getTurns().getFirst().content());
        assertGuardReleased();
    }

    @Test
    void asynchronousFailureNotificationThrowingStillReleasesGuard() {
        CompletableFuture<String> generated = new CompletableFuture<>();
        chat.response = generated;
        command.handle(event);
        assertGuardHeld();
        doThrow(new IllegalStateException("Notification unavailable"))
                .when(hook).editOriginal(DELIVERY_ERROR);
        assertDoesNotThrow(() -> generated.completeExceptionally(new IllegalStateException("AI failed")));
        verify(hook).editOriginal(DELIVERY_ERROR);
        assertGuardReleased();
    }

    @Test
    void synchronousFailureNotificationThrowingStillReleasesGuard() {
        chat.startFailure = new IllegalStateException("Could not start generation");
        IllegalStateException notificationFailure = new IllegalStateException("Notification unavailable");
        doThrow(notificationFailure).when(hook).editOriginal(DELIVERY_ERROR);
        // The mocked JDA callback propagates the notification exception to the caller.
        assertSame(notificationFailure, assertThrows(IllegalStateException.class, () -> command.handle(event)));
        assertGuardReleased();
    }

    @Test
    void synchronousSendStartupFailureReleasesGuard() {
        doThrow(new IllegalStateException("Cannot create send action")).when(thread).sendMessage("Hola");
        command.handle(event);
        verify(hook).editOriginal(DELIVERY_ERROR);
        assertTrue(session().getTurns().isEmpty());
        assertGuardReleased();
    }

    private void assertGuardHeld() {
        assertFalse(session().tryAcquireLock(), "Initial response must retain its guard until work finishes");
    }

    private void assertGuardReleased() {
        assertTrue(session().tryAcquireLock(), "Initial response must release its guard after completion");
        session().releaseLock();
    }

    private void assertGenerationRejected() {
        verify(hook).editOriginal(DELIVERY_ERROR);
        assertGuardReleased();
        verify(event, never()).reply(anyString());
        assertTrue(submitted.isEmpty());
        assertTrue(session().getTurns().isEmpty());
    }

    private ConversationSession session() {
        return manager.getSession(THREAD_ID).orElseThrow();
    }

    private void assertRejectedBeforeWork() {
        verify(reply).setEphemeral(true);
        verify(reply).queue();
        verify(event, never()).deferReply();
        verifyNoInteractions(limiter, parent, manager);
        assertEquals(0, chat.calls);
    }

    private void option(String name, String text) {
        OptionMapping mapping = mock(OptionMapping.class);
        when(mapping.getAsString()).thenReturn(text);
        when(event.getOption(name)).thenReturn(mapping);
    }

    private static class FakeChatService implements ChatService {
        private CompletableFuture<String> response = CompletableFuture.completedFuture("Hola");
        private List<ConversationTurn> history;
        private int calls;
        private RuntimeException startFailure;
        @Override
        public CompletableFuture<String> ask(String prompt) {
            throw new AssertionError("Conversation command must use reply, not ask");
        }
        @Override
        public CompletableFuture<String> reply(List<ConversationTurn> turns) {
            calls++;
            if (startFailure != null) throw startFailure;
            history = List.copyOf(turns);
            return response;
        }
    }
}
