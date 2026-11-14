package dev.romulus_lanceues.ai;

import com.google.genai.AsyncModels;
import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponsePromptFeedback;
import dev.romulus_lanceues.config.GeminiConfig;
import dev.romulus_lanceues.memory.ConversationTurn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GeminiChatServiceTest {

    private Client client;
    private AsyncModels asyncModels;
    private GeminiConfig config;
    private GeminiChatService chatService;

    @BeforeEach
    void setUp() {
        client = mock(Client.class);
        Client.Async async = mock(Client.Async.class);
        asyncModels = mock(AsyncModels.class);

        // Client fields are public final, so Mockito reflection or direct assignment works
        try {
            var asyncField = Client.class.getField("async");
            asyncField.setAccessible(true);
            // On a mock, public fields might be null unless stubbed or set
            var fieldSetter = Client.class.getDeclaredField("async");
            fieldSetter.setAccessible(true);
            fieldSetter.set(client, async);

            var modelsField = Client.Async.class.getDeclaredField("models");
            modelsField.setAccessible(true);
            modelsField.set(async, asyncModels);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject mocked async fields", e);
        }

        config = new GeminiConfig(
                "fake-api-key",
                "gemini-2.5-flash",
                0.7f,
                0.95f,
                40.0f,
                2048,
                "Test instruction"
        );
        chatService = new GeminiChatService(client, config, Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("Returns generated text on successful response")
    void returnsGeneratedTextOnSuccess() {
        GenerateContentResponse response = mock(GenerateContentResponse.class);
        when(response.finishReason()).thenReturn(new FinishReason(FinishReason.Known.STOP));
        when(response.promptFeedback()).thenReturn(Optional.empty());
        when(response.text()).thenReturn("Hello! This is Cloud.");

        when(asyncModels.generateContent(eq("gemini-2.5-flash"), eq("Hi"), any(GenerateContentConfig.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        String result = chatService.ask("Hi").join();
        assertEquals("Hello! This is Cloud.", result);
    }

    @Test
    void replyRejectsNullHistoryWithoutCallingGemini() {
        assertThrows(NullPointerException.class, () -> chatService.reply(null));
        verifyNoInteractions(asyncModels);
    }

    @Test
    void replyRejectsEmptyHistoryWithoutCallingGemini() {
        assertThrows(IllegalArgumentException.class, () -> chatService.reply(List.of()));
        verifyNoInteractions(asyncModels);
    }

    @Test
    void replySendsOrderedAttributedHistoryInOneRequestWithConfiguredSettings() {
        List<ConversationTurn> history = List.of(
                ConversationTurn.user("Alice", "Explain Java in Spanish"),
                ConversationTurn.bot("Java es un lenguaje de programación."),
                ConversationTurn.user("Bob", "What is a class?"),
                ConversationTurn.user("Alice", "Give us an example too")
        );
        stubReplyFuture(CompletableFuture.completedFuture(successfulResponse("  Una clase...  ")));
        ArgumentCaptor<List<Content>> messages = ArgumentCaptor.captor();
        ArgumentCaptor<GenerateContentConfig> settings = ArgumentCaptor.forClass(GenerateContentConfig.class);

        assertEquals("Una clase...", chatService.reply(history).join());

        verify(asyncModels).generateContent(eq(config.model()), messages.capture(), settings.capture());
        verifyNoMoreInteractions(asyncModels);
        List<Content> sent = messages.getValue();
        assertEquals(4, sent.size());
        assertMessage(sent.get(0), "user", "[Alice]: Explain Java in Spanish");
        assertMessage(sent.get(1), "model", "Java es un lenguaje de programación.");
        assertMessage(sent.get(2), "user", "[Bob]: What is a class?");
        assertMessage(sent.get(3), "user", "[Alice]: Give us an example too");

        GenerateContentConfig sentSettings = settings.getValue();
        assertEquals(config.systemInstruction(),
                sentSettings.systemInstruction().orElseThrow().parts().orElseThrow()
                        .getFirst().text().orElseThrow());
        assertEquals(Optional.of(config.temperature()), sentSettings.temperature());
        assertEquals(Optional.of(config.topP()), sentSettings.topP());
        assertEquals(Optional.of(config.topK()), sentSettings.topK());
        assertEquals(Optional.of(config.maxOutputTokens()), sentSettings.maxOutputTokens());
        assertEquals(4, history.size());
    }

    @Test
    void replySupportsOnlyThePinnedInitialPrompt() {
        stubReplyFuture(CompletableFuture.completedFuture(successfulResponse("Hola")));
        ArgumentCaptor<List<Content>> messages = ArgumentCaptor.captor();

        assertEquals("Hola", chatService.reply(initialHistory()).join());

        verify(asyncModels).generateContent(eq(config.model()), messages.capture(), any(GenerateContentConfig.class));
        assertEquals(1, messages.getValue().size());
        assertMessage(messages.getValue().getFirst(), "user", "[Alice]: Answer in Spanish");
    }

    @Test
    void replyReturnsBeforeGeminiCompletesAndProcessesTheEventualResponse() {
        CompletableFuture<GenerateContentResponse> pending = new CompletableFuture<>();
        stubReplyFuture(pending);

        CompletableFuture<String> result = chatService.reply(initialHistory());

        assertFalse(result.isDone());
        pending.complete(successfulResponse("Hola"));
        assertEquals("Hola", result.join());
    }

    @Test
    void replyHandlesRateLimitFailure() {
        stubReplyFuture(CompletableFuture.failedFuture(
                new ApiException(429, "RESOURCE_EXHAUSTED", "Quota exceeded")));

        assertEquals(GeminiChatService.RATE_LIMIT_MESSAGE, chatService.reply(initialHistory()).join());
    }

    @Test
    void replyHandlesTimeoutFailure() {
        // Complete exceptionally instead of waiting for a real timer.
        stubReplyFuture(CompletableFuture.failedFuture(new TimeoutException("Timed out")));

        assertEquals(GeminiChatService.TIMEOUT_MESSAGE, chatService.reply(initialHistory()).join());
    }

    @Test
    void replyHandlesUnexpectedFailure() {
        stubReplyFuture(CompletableFuture.failedFuture(new IllegalStateException("Network failure")));

        assertEquals(GeminiChatService.GENERIC_ERROR_MESSAGE, chatService.reply(initialHistory()).join());
    }

    @Test
    void replyHandlesSafetyBlockedResponse() {
        GenerateContentResponse response = mock(GenerateContentResponse.class);
        when(response.finishReason()).thenReturn(new FinishReason(FinishReason.Known.SAFETY));
        stubReplyFuture(CompletableFuture.completedFuture(response));

        assertEquals(GeminiChatService.SAFETY_BLOCK_MESSAGE, chatService.reply(initialHistory()).join());
    }

    @Test
    void replyHandlesBlankResponse() {
        stubReplyFuture(CompletableFuture.completedFuture(successfulResponse("   ")));

        assertEquals(GeminiChatService.SAFETY_BLOCK_MESSAGE, chatService.reply(initialHistory()).join());
    }

    @Test
    void replyHandlesNullResponse() {
        stubReplyFuture(CompletableFuture.completedFuture(null));

        assertEquals(GeminiChatService.GENERIC_ERROR_MESSAGE, chatService.reply(initialHistory()).join());
    }

    private List<ConversationTurn> initialHistory() {
        return List.of(ConversationTurn.user("Alice", "Answer in Spanish"));
    }

    private GenerateContentResponse successfulResponse(String text) {
        GenerateContentResponse response = mock(GenerateContentResponse.class);
        when(response.promptFeedback()).thenReturn(Optional.empty());
        when(response.text()).thenReturn(text);
        return response;
    }

    private void stubReplyFuture(CompletableFuture<GenerateContentResponse> future) {
        when(asyncModels.generateContent(eq(config.model()),
                org.mockito.ArgumentMatchers.<Content>anyList(), any(GenerateContentConfig.class)))
                .thenReturn(future);
    }

    private void assertMessage(Content message, String role, String text) {
        assertEquals(role, message.role().orElseThrow());
        assertEquals(1, message.parts().orElseThrow().size());
        assertEquals(text, message.parts().orElseThrow().getFirst().text().orElseThrow());
    }

    @Test
    @DisplayName("Returns friendly message when response is flagged by safety guidelines")
    void handlesSafetyBlock() {
        GenerateContentResponse response = mock(GenerateContentResponse.class);
        when(response.finishReason()).thenReturn(new FinishReason(FinishReason.Known.SAFETY));
        when(response.promptFeedback()).thenReturn(Optional.empty());

        when(asyncModels.generateContent(anyString(), anyString(), any(GenerateContentConfig.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        String result = chatService.ask("Unsafe prompt").join();
        assertEquals("I'm unable to answer that because the response was flagged by safety guidelines.", result);
    }

    @Test
    @DisplayName("Returns friendly message when response text throws safety IllegalArgumentException")
    void handlesSafetyExceptionFromTextExtraction() {
        GenerateContentResponse response = mock(GenerateContentResponse.class);
        when(response.finishReason()).thenReturn(null);
        when(response.promptFeedback()).thenReturn(Optional.empty());
        when(response.text()).thenThrow(new IllegalArgumentException("The response finished unexpectedly with reason SAFETY."));

        when(asyncModels.generateContent(anyString(), anyString(), any(GenerateContentConfig.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        String result = chatService.ask("Unsafe prompt").join();
        assertEquals("I'm unable to answer that because the response was flagged by safety guidelines.", result);
    }

    @Test
    @DisplayName("Returns rate limit friendly message when 429 ApiException occurs")
    void handlesRateLimitException() {
        ApiException apiException = new ApiException(429, "RESOURCE_EXHAUSTED", "Quota exceeded");
        CompletableFuture<GenerateContentResponse> failedFuture = CompletableFuture.failedFuture(apiException);

        when(asyncModels.generateContent(anyString(), anyString(), any(GenerateContentConfig.class)))
                .thenReturn(failedFuture);

        String result = chatService.ask("Any prompt").join();
        assertEquals("I'm receiving too many requests right now. Please wait a moment and try again.", result);
    }

    @Test
    @DisplayName("Returns timeout friendly message when request times out")
    void handlesTimeoutException() {
        CompletableFuture<GenerateContentResponse> failedFuture = CompletableFuture.failedFuture(new TimeoutException("Timed out"));

        when(asyncModels.generateContent(anyString(), anyString(), any(GenerateContentConfig.class)))
                .thenReturn(failedFuture);

        String result = chatService.ask("Slow prompt").join();
        assertEquals("Request timed out while waiting for Gemini to respond. Please try again.", result);
    }

    @Test
    @DisplayName("Returns generic error friendly message on unexpected exception")
    void handlesUnexpectedException() {
        CompletableFuture<GenerateContentResponse> failedFuture = CompletableFuture.failedFuture(new RuntimeException("Network down"));

        when(asyncModels.generateContent(anyString(), anyString(), any(GenerateContentConfig.class)))
                .thenReturn(failedFuture);

        String result = chatService.ask("Fail prompt").join();
        assertEquals("I encountered an unexpected issue while generating a response. Please try again later.", result);
    }

    @Test
    @DisplayName("Returns friendly safety message when response text is empty or blank")
    void handlesEmptyOrBlankResponseText() {
        GenerateContentResponse response = mock(GenerateContentResponse.class);
        when(response.finishReason()).thenReturn(null);
        when(response.promptFeedback()).thenReturn(Optional.empty());
        when(response.text()).thenReturn("   ");

        when(asyncModels.generateContent(anyString(), anyString(), any(GenerateContentConfig.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        String result = chatService.ask("Prompt that gives blank").join();
        assertEquals("I'm unable to answer that because the response was flagged by safety guidelines.", result);
    }

    @Test
    @DisplayName("Returns friendly safety message when response text is null")
    void handlesNullResponseText() {
        GenerateContentResponse response = mock(GenerateContentResponse.class);
        when(response.finishReason()).thenReturn(null);
        when(response.promptFeedback()).thenReturn(Optional.empty());
        when(response.text()).thenReturn(null);

        when(asyncModels.generateContent(anyString(), anyString(), any(GenerateContentConfig.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        String result = chatService.ask("Prompt that gives null text").join();
        assertEquals("I'm unable to answer that because the response was flagged by safety guidelines.", result);
    }

    @Test
    @DisplayName("Returns generic error when response object itself is null")
    void handlesNullResponseObject() {
        when(asyncModels.generateContent(anyString(), anyString(), any(GenerateContentConfig.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        String result = chatService.ask("Null response").join();
        assertEquals("I encountered an unexpected issue while generating a response. Please try again later.", result);
    }

    @Test
    @DisplayName("Returns rate limit friendly message when ApiException message mentions quota")
    void handlesQuotaMentionInApiExceptionMessage() {
        ApiException apiException = new ApiException(403, "RESOURCE_EXHAUSTED", "quota exceeded for quota metric");
        CompletableFuture<GenerateContentResponse> failedFuture = CompletableFuture.failedFuture(apiException);

        when(asyncModels.generateContent(anyString(), anyString(), any(GenerateContentConfig.class)))
                .thenReturn(failedFuture);

        String result = chatService.ask("Quota prompt").join();
        assertEquals("I'm receiving too many requests right now. Please wait a moment and try again.", result);
    }

    @Test
    @DisplayName("Returns friendly safety message for various safety finish reasons like BLOCKLIST")
    void handlesBlocklistFinishReason() {
        GenerateContentResponse response = mock(GenerateContentResponse.class);
        when(response.finishReason()).thenReturn(new FinishReason(FinishReason.Known.BLOCKLIST));
        when(response.promptFeedback()).thenReturn(Optional.empty());

        when(asyncModels.generateContent(anyString(), anyString(), any(GenerateContentConfig.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        String result = chatService.ask("Blocklisted").join();
        assertEquals("I'm unable to answer that because the response was flagged by safety guidelines.", result);
    }

    @Test
    @DisplayName("Supports 2-arg constructor with default 30-second timeout")
    void supportsDefaultConstructor() {
        GeminiChatService defaultService = new GeminiChatService(client, config);
        GenerateContentResponse response = mock(GenerateContentResponse.class);
        when(response.finishReason()).thenReturn(new FinishReason(FinishReason.Known.STOP));
        when(response.promptFeedback()).thenReturn(Optional.empty());
        when(response.text()).thenReturn("Default timeout response");

        when(asyncModels.generateContent(anyString(), anyString(), any(GenerateContentConfig.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        String result = defaultService.ask("Hi").join();
        assertEquals("Default timeout response", result);
    }
}
