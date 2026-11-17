package dev.romulus_lanceues.discord;

import dev.romulus_lanceues.memory.ConversationTurn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ConversationFlowIntegrationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createdConversationCarriesContextIntoMentionsAndRepliesWithoutMentions(boolean directReply) {
        ConversationTestFixture fixture = new ConversationTestFixture();
        fixture.commands.onSlashCommandInteraction(fixture.slash("conversation", "Answer in Spanish"));
        var session = fixture.manager.getSession(ConversationTestFixture.THREAD_ID).orElseThrow();

        fixture.messages.onMessageReceived(fixture.message(84L, "Bob", "We chose blue", false, false));
        assertEquals(1, fixture.chat.contexts.size());
        assertEquals(List.of("Answer 1"), fixture.delivered);
        fixture.messages.onMessageReceived(fixture.message(84L, "Bob",
                directReply ? "What did we choose?" : "<@99> What did we choose?", !directReply, directReply));

        assertSame(session, fixture.manager.getSession(ConversationTestFixture.THREAD_ID).orElseThrow());
        assertEquals(2, fixture.chat.contexts.size());
        List<ConversationTurn> context = fixture.chat.contexts.getLast();
        assertSame(session.getInitialPrompt(), context.getFirst());
        assertEquals(List.of("Answer in Spanish", "Answer 1", "We chose blue", "What did we choose?"),
                context.stream().map(ConversationTurn::content).toList());
        assertEquals(List.of("Alice", "cloud", "Bob", "Bob"),
                context.stream().map(ConversationTurn::speaker).toList());
        assertTrue(context.get(1).isBot());
        assertFalse(context.getLast().isBot());
        assertEquals(List.of("Answer 1", "Answer 2"), fixture.delivered);
        assertEquals("Answer 2", session.getTurns().getLast().content());
        assertTrue(session.tryAcquireLock());
        session.releaseLock();
        assertTrue(fixture.chat.asks.isEmpty());
    }

    @Test
    void initialResponseGuardBlocksMentionsUntilCommandDeliveryCompletes() {
        ConversationTestFixture fixture = new ConversationTestFixture();
        CompletableFuture<String> initial = new CompletableFuture<>();
        fixture.chat.responses.add(initial);
        fixture.commands.onSlashCommandInteraction(fixture.slash("conversation", "Initial instructions"));
        var session = fixture.manager.getSession(ConversationTestFixture.THREAD_ID).orElseThrow();

        fixture.messages.onMessageReceived(fixture.message(84L, "Bob", "<@99> First question", true, false));

        assertEquals(1, fixture.chat.contexts.size());
        assertEquals(List.of("I'm already preparing a response. Please try again after I finish."), fixture.delivered);
        assertFalse(session.tryAcquireLock());
        assertTrue(session.getTurns().isEmpty());
        initial.complete("Initial answer");
        assertTrue(session.tryAcquireLock());
        session.releaseLock();
        fixture.messages.onMessageReceived(fixture.message(85L, "Carol", "<@99> Next question", true, false));
        assertEquals(2, fixture.chat.contexts.size());
        assertEquals(List.of("Initial instructions", "Initial answer", "Next question"),
                fixture.chat.contexts.getLast().stream().map(ConversationTurn::content).toList());
    }

    @Test
    void archiveRetiresCommandCreatedSessionAndUnarchiveDoesNotRestoreIt() {
        ConversationTestFixture fixture = new ConversationTestFixture();
        fixture.commands.onSlashCommandInteraction(fixture.slash("conversation", "Initial instructions"));
        var original = fixture.manager.getSession(ConversationTestFixture.THREAD_ID).orElseThrow();

        fixture.messages.onChannelUpdateArchived(fixture.archive(true));
        fixture.messages.onMessageReceived(fixture.message(84L, "Bob", "<@99> Question", true, false));
        fixture.messages.onChannelUpdateArchived(fixture.archive(false));
        fixture.messages.onMessageReceived(fixture.message(85L, "Carol", "<@99> Question", true, false));

        assertTrue(fixture.manager.getSession(ConversationTestFixture.THREAD_ID).isEmpty());
        assertFalse(original.verifyStateEligibility(dev.romulus_lanceues.memory.ConversationManager.TIME_TO_LIVE_DURATION));
        assertEquals(1, fixture.chat.contexts.size());
        assertEquals(List.of("Answer 1"), fixture.delivered);
    }
}
