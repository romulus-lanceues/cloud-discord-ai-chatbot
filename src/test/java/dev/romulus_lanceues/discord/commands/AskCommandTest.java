package dev.romulus_lanceues.discord.commands;

import dev.romulus_lanceues.ai.ChatService;
import dev.romulus_lanceues.util.RateLimiter;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AskCommandTest {

    private ChatService chatService;
    private RateLimiter rateLimiter;
    private AskCommand command;
    private SlashCommandInteractionEvent event;
    private ReplyCallbackAction replyAction;
    private ReplyCallbackAction deferAction;
    private InteractionHook hook;
    private WebhookMessageEditAction editAction;
    private WebhookMessageCreateAction createAction;
    private User user;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chatService = mock(ChatService.class);
        rateLimiter = mock(RateLimiter.class);
        command = new AskCommand(chatService, rateLimiter);

        event = mock(SlashCommandInteractionEvent.class);
        replyAction = mock(ReplyCallbackAction.class);
        deferAction = mock(ReplyCallbackAction.class);
        hook = mock(InteractionHook.class);
        editAction = mock(WebhookMessageEditAction.class);
        createAction = mock(WebhookMessageCreateAction.class);
        user = mock(User.class);

        when(event.getUser()).thenReturn(user);
        when(user.getIdLong()).thenReturn(12345L);

        when(event.reply(anyString())).thenReturn(replyAction);
        when(replyAction.setEphemeral(anyBoolean())).thenReturn(replyAction);

        when(event.deferReply()).thenReturn(deferAction);

        when(event.getHook()).thenReturn(hook);
        when(hook.editOriginal(anyString())).thenReturn(editAction);
        when(editAction.setAllowedMentions(any())).thenReturn(editAction);
        when(editAction.submit()).thenReturn(CompletableFuture.completedFuture(mock(Message.class)));

        when(hook.sendMessage(anyString())).thenReturn(createAction);
        when(createAction.setAllowedMentions(any())).thenReturn(createAction);
        when(createAction.submit()).thenReturn(CompletableFuture.completedFuture(mock(Message.class)));

        when(rateLimiter.checkCooldown(anyLong())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("Has correct command name, description, and required prompt option attributes")
    void hasCorrectNameAndCommandData() {
        assertEquals("ask", command.name());

        SlashCommandData data = command.commandData();
        assertEquals("ask", data.getName());
        assertEquals("Ask Cloud a question or prompt.", data.getDescription());
        assertEquals(1, data.getOptions().size());

        OptionData promptOption = data.getOptions().get(0);
        assertEquals("prompt", promptOption.getName());
        assertEquals(OptionType.STRING, promptOption.getType());
        assertEquals("The question or prompt to send to Cloud.", promptOption.getDescription());
        assertTrue(promptOption.isRequired());
    }

    @ParameterizedTest(name = "Rejects invalid whitespace prompt: {0}")
    @ValueSource(strings = {
            "",
            "   ",
            "\t",
            "\n",
            "\r\n\t  \n",
            "\u2000\u2003\u3000"
    })
    @DisplayName("Rejects blank prompts with ephemeral warning")
    void rejectsBlankPromptWithEphemeralWarning(String invalidPrompt) {
        OptionMapping promptOption = mock(OptionMapping.class);
        when(promptOption.getAsString()).thenReturn(invalidPrompt);
        when(event.getOption("prompt")).thenReturn(promptOption);

        command.handle(event);

        verify(event).reply("Please provide a non-empty prompt.");
        verify(replyAction).setEphemeral(true);
        verify(replyAction).queue();
        verify(event, never()).deferReply();
        verifyNoInteractions(chatService);
    }

    @Test
    @DisplayName("Rejects null prompt option with ephemeral warning")
    void rejectsNullPromptWithEphemeralWarning() {
        when(event.getOption("prompt")).thenReturn(null);

        command.handle(event);

        verify(event).reply("Please provide a non-empty prompt.");
        verify(replyAction).setEphemeral(true);
        verify(replyAction).queue();
        verify(event, never()).deferReply();
        verifyNoInteractions(chatService);
    }

    @Test
    @DisplayName("Replies ephemerally when user is in cooldown")
    void blocksUserInCooldown() {
        OptionMapping promptOption = mock(OptionMapping.class);
        when(promptOption.getAsString()).thenReturn("Valid question");
        when(event.getOption("prompt")).thenReturn(promptOption);

        when(rateLimiter.checkCooldown(12345L)).thenReturn(Optional.of(Duration.ofSeconds(4)));

        command.handle(event);

        verify(event).reply("⏳ Please wait 4 seconds before asking another question.");
        verify(replyAction).setEphemeral(true);
        verify(replyAction).queue();
        verify(event, never()).deferReply();
        verifyNoInteractions(chatService);
    }

    @Test
    @DisplayName("Defers reply and edits original with answer under 2000 characters")
    void defersAndEditsOriginalOnSingleChunk() {
        OptionMapping promptOption = mock(OptionMapping.class);
        when(promptOption.getAsString()).thenReturn("What is Java?");
        when(event.getOption("prompt")).thenReturn(promptOption);

        when(chatService.ask("What is Java?"))
                .thenReturn(CompletableFuture.completedFuture("Java is an object-oriented programming language."));

        command.handle(event);

        verify(event.deferReply()).queue();
        verify(chatService).ask("What is Java?");
        verify(hook).editOriginal("Java is an object-oriented programming language.");
        verify(editAction).setAllowedMentions(Collections.emptyList());
        verify(editAction).submit();
        verify(hook, never()).sendMessage(anyString());
    }

    @Test
    @DisplayName("Splits long response into multiple chunks and sends subsequent messages")
    void splitsAndSendsSubsequentMessagesForLongResponse() {
        OptionMapping promptOption = mock(OptionMapping.class);
        when(promptOption.getAsString()).thenReturn("Explain quantum mechanics");
        when(event.getOption("prompt")).thenReturn(promptOption);

        String chunk1 = "A".repeat(1500);
        String chunk2 = "B".repeat(1000);
        String fullResponse = chunk1 + "\n\n" + chunk2;

        when(chatService.ask(anyString()))
                .thenReturn(CompletableFuture.completedFuture(fullResponse));

        command.handle(event);

        verify(event.deferReply()).queue();
        ArgumentCaptor<String> editCaptor = ArgumentCaptor.forClass(String.class);
        verify(hook).editOriginal(editCaptor.capture());
        verify(editAction).setAllowedMentions(Collections.emptyList());
        verify(editAction).submit();

        ArgumentCaptor<String> sendCaptor = ArgumentCaptor.forClass(String.class);
        verify(hook).sendMessage(sendCaptor.capture());
        verify(createAction).setAllowedMentions(Collections.emptyList());
        verify(createAction).submit();

        assertEquals(chunk1, editCaptor.getValue());
        assertEquals(chunk2, sendCaptor.getValue());
    }

    @Test
    @DisplayName("Edits original with friendly error when chatService future fails")
    void handlesChatServiceFailureGracefully() {
        OptionMapping promptOption = mock(OptionMapping.class);
        when(promptOption.getAsString()).thenReturn("Fail question");
        when(event.getOption("prompt")).thenReturn(promptOption);

        when(chatService.ask(anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Crash")));

        command.handle(event);

        verify(event.deferReply()).queue();
        verify(hook).editOriginal("I encountered an unexpected issue while generating a response. Please try again later.");
        verify(editAction).setAllowedMentions(Collections.emptyList());
        verify(editAction).queue(any(), any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\n\t"})
    @DisplayName("Edits original with friendly error when chatService returns empty string")
    void handlesChatServiceEmptyResponseGracefully(String result) {
        OptionMapping promptOption = mock(OptionMapping.class);
        when(promptOption.getAsString()).thenReturn("Empty response question");
        when(event.getOption("prompt")).thenReturn(promptOption);

        when(chatService.ask(anyString()))
                .thenReturn(CompletableFuture.completedFuture(result));

        command.handle(event);

        verify(event.deferReply()).queue();
        verify(hook).editOriginal("I encountered an unexpected issue while generating a response. Please try again later.");
        verify(editAction).setAllowedMentions(Collections.emptyList());
        verify(editAction).queue(any(), any());
    }

    @Test
    void synchronousGenerationFailureEditsDeferredResponseOnce() {
        prompt("Question");
        when(chatService.ask("Question")).thenThrow(new IllegalStateException("Startup failed"));

        assertDoesNotThrow(() -> command.handle(event));

        verify(deferAction).queue();
        verify(hook).editOriginal(AskCommand.GENERATION_FAILED);
        verify(editAction).setAllowedMentions(Collections.emptyList());
        verify(editAction).queue(any(), any());
        verify(editAction, never()).submit();
        verify(hook, never()).sendMessage(anyString());
    }

    @Test
    void waitsForOriginalEditAndEachFollowupBeforeSubmittingTheNextChunk() {
        prompt("Long question");
        when(chatService.ask(anyString())).thenReturn(CompletableFuture.completedFuture("x".repeat(4500)));
        CompletableFuture<Message> first = new CompletableFuture<>();
        CompletableFuture<Message> second = new CompletableFuture<>();
        CompletableFuture<Message> third = new CompletableFuture<>();
        when(editAction.submit()).thenReturn(first);
        when(createAction.submit()).thenReturn(second, third);

        command.handle(event);

        verify(editAction).submit();
        verify(hook, never()).sendMessage(anyString());
        first.complete(mock(Message.class));
        verify(hook, times(1)).sendMessage("x".repeat(2000));
        verify(createAction, times(1)).submit();
        second.complete(mock(Message.class));
        verify(hook).sendMessage("x".repeat(500));
        verify(createAction, times(2)).submit();
        third.complete(mock(Message.class));
        verify(editAction).setAllowedMentions(Collections.emptyList());
        verify(createAction, times(2)).setAllowedMentions(Collections.emptyList());
        verify(editAction, never()).queue(any(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    @SuppressWarnings("unchecked")
    void failedDeliveryStopsRemainingChunksWithoutOverwritingDeliveredText(int failedIndex) {
        prompt("Long question");
        when(chatService.ask(anyString())).thenReturn(CompletableFuture.completedFuture("x".repeat(4500)));
        List<CompletableFuture<Message>> deliveries = new ArrayList<>();
        for (int i = 0; i < 3; i++) deliveries.add(new CompletableFuture<>());
        when(editAction.submit()).thenReturn(deliveries.get(0));
        when(createAction.submit()).thenReturn(deliveries.get(1), deliveries.get(2));

        command.handle(event);
        for (int i = 0; i < failedIndex; i++) deliveries.get(i).complete(mock(Message.class));
        deliveries.get(failedIndex).completeExceptionally(new IllegalStateException("Delivery failed"));

        verify(hook, times(1)).editOriginal(anyString());
        verify(hook, never()).editOriginal(AskCommand.GENERATION_FAILED);
        verify(hook, times(failedIndex)).sendMessage(anyString());
        verify(createAction, times(failedIndex)).submit();
    }

    @ParameterizedTest
    @ValueSource(strings = {"edit-creation", "edit-submission", "followup-creation", "followup-submission"})
    void synchronousDeliveryFailureDoesNotEscapeOrSendLaterChunks(String stage) {
        prompt("Long question");
        when(chatService.ask(anyString())).thenReturn(CompletableFuture.completedFuture("x".repeat(4500)));
        RuntimeException failure = new IllegalStateException("Delivery startup failed");
        switch (stage) {
            case "edit-creation" -> when(hook.editOriginal("x".repeat(2000))).thenThrow(failure);
            case "edit-submission" -> when(editAction.submit()).thenThrow(failure);
            case "followup-creation" -> when(hook.sendMessage(anyString())).thenThrow(failure);
            case "followup-submission" -> when(createAction.submit()).thenThrow(failure);
        }

        assertDoesNotThrow(() -> command.handle(event));

        verify(hook, never()).editOriginal(AskCommand.GENERATION_FAILED);
        verify(hook, never()).sendMessage("x".repeat(500));
        if (stage.startsWith("edit")) verify(hook, never()).sendMessage(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"creation", "submission", "callback"})
    void failureNotificationErrorsDoNotEscapeOrTriggerAnotherNotification(String stage) {
        prompt("Question");
        when(chatService.ask(anyString())).thenThrow(new IllegalStateException("Generation failed"));
        RuntimeException failure = new IllegalStateException("Notification unavailable");
        switch (stage) {
            case "creation" -> when(hook.editOriginal(AskCommand.GENERATION_FAILED)).thenThrow(failure);
            case "submission" -> doThrow(failure).when(editAction).queue(any(), any());
            case "callback" -> doAnswer(call -> {
                call.<Consumer<Throwable>>getArgument(1).accept(failure);
                return null;
            }).when(editAction).queue(any(), any());
        }

        assertDoesNotThrow(() -> command.handle(event));

        verify(hook, times(1)).editOriginal(AskCommand.GENERATION_FAILED);
        verify(hook, never()).sendMessage(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void overlappingUsersReceiveTheirOwnAnswersWhenGenerationFinishesInReverseOrder() {
        command = new AskCommand(chatService, new RateLimiter(Duration.ofSeconds(5),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)));
        prompt("First question");
        SlashCommandInteractionEvent otherEvent = mock(SlashCommandInteractionEvent.class);
        User otherUser = mock(User.class);
        when(otherUser.getIdLong()).thenReturn(999L);
        when(otherEvent.getUser()).thenReturn(otherUser);
        OptionMapping otherPrompt = mock(OptionMapping.class);
        when(otherPrompt.getAsString()).thenReturn("Second question");
        when(otherEvent.getOption("prompt")).thenReturn(otherPrompt);
        when(otherEvent.deferReply()).thenReturn(mock(ReplyCallbackAction.class));
        InteractionHook otherHook = mock(InteractionHook.class);
        when(otherEvent.getHook()).thenReturn(otherHook);
        WebhookMessageEditAction<Message> otherEdit = mock(WebhookMessageEditAction.class, RETURNS_SELF);
        when(otherHook.editOriginal(anyString())).thenReturn(otherEdit);
        when(otherEdit.submit()).thenReturn(CompletableFuture.completedFuture(mock(Message.class)));
        CompletableFuture<String> first = new CompletableFuture<>();
        CompletableFuture<String> second = new CompletableFuture<>();
        when(chatService.ask("First question")).thenReturn(first);
        when(chatService.ask("Second question")).thenReturn(second);

        command.handle(event);
        command.handle(otherEvent);
        verify(chatService).ask("First question");
        verify(chatService).ask("Second question");
        verify(hook, never()).editOriginal(anyString());
        verify(otherHook, never()).editOriginal(anyString());
        second.complete("Second answer");
        verify(otherHook).editOriginal("Second answer");
        verify(hook, never()).editOriginal(anyString());
        first.complete("First answer");
        verify(hook).editOriginal("First answer");
        verify(hook, never()).editOriginal("Second answer");
        verify(otherHook, never()).editOriginal("First answer");
    }

    private void prompt(String text) {
        OptionMapping option = mock(OptionMapping.class);
        when(option.getAsString()).thenReturn(text);
        when(event.getOption("prompt")).thenReturn(option);
    }

    @Test
    @DisplayName("Validates constructor parameters for null checks")
    void validatesConstructorParameters() {
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> new AskCommand(null, rateLimiter));
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> new AskCommand(chatService, null));
    }
}
