package dev.romulus_lanceues.ai;

import dev.romulus_lanceues.memory.ConversationTurn;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ChatService {
    CompletableFuture<String> ask(String prompt);

    CompletableFuture<String> reply(List<ConversationTurn> conversationHistory);
}
