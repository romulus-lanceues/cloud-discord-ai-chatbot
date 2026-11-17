package dev.romulus_lanceues.memory;

import java.time.Instant;
import java.util.Objects;

public record ConversationTurn(
        String speaker,
        String content,
        Instant timestamp,
        boolean isBot
) {
    public ConversationTurn {
        Objects.requireNonNull(speaker, "Speaker cannot be null");
        Objects.requireNonNull(content, "Content cannot be null");
        content = content.trim();
        Objects.requireNonNull(timestamp, "Time stamp cannot be null");
    }

    public static ConversationTurn user(String speaker, String content){
        return new ConversationTurn(speaker, content, Instant.now(), false);
    }

    public static ConversationTurn bot (String content){
        return new ConversationTurn("cloud", content, Instant.now(), true);
    }
}
