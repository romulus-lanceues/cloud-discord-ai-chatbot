package dev.romulus_lanceues.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

public class ConversationTurnTest {

    @Test
    void createsHumanTurn() {
        String username = "Alice";
        String content = "This is a test content";

        ConversationTurn humanTurn = ConversationTurn.user(username, content);

        assertEquals(username, humanTurn.speaker());
        assertEquals(content, humanTurn.content());
        assertFalse(humanTurn.isBot());
    }

    @Test
    void createsBotTurn() {
        String username = "cloud";
        String content = "That's a great question! The answer for that is ...";

        ConversationTurn botTurn = ConversationTurn.bot(content);

        assertEquals(username, botTurn.speaker());
        assertEquals(content, botTurn.content());
        assertTrue(botTurn.isBot());
    }

    @Test
    void contentWithSurroundingWhitespace() {
        String username = "Bob";
        String content = "           This is a test content    ";

        ConversationTurn userTurn = ConversationTurn.user(username, content);

        assertEquals("This is a test content", userTurn.content());
    }

    @Test
    void contentWithInternalWhitespace() {
        String username = "Desmond";
        String content = "Desmond   Jones";

        ConversationTurn userTurn = ConversationTurn.user(username, content);

        assertEquals(content, userTurn.content());
    }

    @Test
    void rejectNullSpeaker(){
        assertThrows(NullPointerException.class,
                () -> new ConversationTurn(null, "Test Content", Instant.now(), false));
    }

    @Test
    void rejectNullContent(){
        assertThrows(NullPointerException.class,
                () -> new ConversationTurn("Trisha", null, Instant.now(), false) );

    }

    @Test
    void rejectNullTimestamp(){
        assertThrows(NullPointerException.class,
                () -> new ConversationTurn("Niko", "Test Content", null, false));
    }

    @Test
    void preservedSuppliedTimestamp(){
        Instant now = Instant.parse("2018-04-20T00:00:00Z");

        ConversationTurn conversationTurn = new ConversationTurn("cloud", "Test Content", now, true);

        assertEquals(now, conversationTurn.timestamp());
    }

}
