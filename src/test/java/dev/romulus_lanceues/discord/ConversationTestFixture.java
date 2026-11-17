package dev.romulus_lanceues.discord;

import dev.romulus_lanceues.ai.ChatService;
import dev.romulus_lanceues.discord.commands.AskCommand;
import dev.romulus_lanceues.discord.commands.CloudCommand;
import dev.romulus_lanceues.discord.commands.ConversationCommand;
import dev.romulus_lanceues.memory.ConversationManager;
import dev.romulus_lanceues.memory.ConversationTurn;
import dev.romulus_lanceues.util.RateLimiter;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Mentions;
import net.dv8tion.jda.api.entities.MessageType;
import net.dv8tion.jda.api.entities.SelfUser;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.unions.ChannelUnion;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.channel.update.ChannelUpdateArchivedEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.ThreadChannelAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.mockito.Mockito.*;

/** Local component integration with real memory/cooldowns and mocked Discord I/O. */
final class ConversationTestFixture {
    static final long THREAD_ID = 123L;
    final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    final ConversationManager manager;
    final FakeChatService chat = new FakeChatService();
    final SlashCommandListener commands;
    final ConversationListener messages;
    final TextChannel parent = mock(TextChannel.class);
    final ThreadChannel thread = mock(ThreadChannel.class);
    final List<String> delivered = new ArrayList<>();
    private final JDA jda = mock(JDA.class);
    private final SelfUser cloud = mock(SelfUser.class);
    private final User alice = user(42L, "Alice");
    private final MessageChannelUnion threadChannel = mock(MessageChannelUnion.class);

    @SuppressWarnings("unchecked")
    ConversationTestFixture() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC);
        manager = new ConversationManager(clock, scheduler);
        RateLimiter limiter = new RateLimiter(Duration.ofSeconds(5), clock);
        commands = new SlashCommandListener(List.of(new CloudCommand(), new AskCommand(chat, limiter),
                new ConversationCommand(limiter, manager, chat, scheduler)));
        messages = new ConversationListener(manager, chat, limiter, scheduler);
        when(scheduler.scheduleWithFixedDelay(any(), eq(5L), eq(5L), eq(TimeUnit.SECONDS)))
                .thenAnswer(call -> mock(ScheduledFuture.class));
        when(thread.getIdLong()).thenReturn(THREAD_ID);
        when(thread.getAsMention()).thenReturn("<#123>");
        when(threadChannel.getIdLong()).thenReturn(THREAD_ID);
        when(jda.getSelfUser()).thenReturn(cloud);
        when(cloud.getId()).thenReturn("99");
        ThreadChannelAction creation = mock(ThreadChannelAction.class);
        when(parent.createThreadChannel(anyString())).thenReturn(creation);
        doAnswer(call -> {
            call.<Consumer<ThreadChannel>>getArgument(0).accept(thread);
            return null;
        }).when(creation).queue(any(), any());
        RestAction<Void> addition = mock(RestAction.class);
        when(thread.addThreadMember(alice)).thenReturn(addition);
        doAnswer(call -> {
            call.<Consumer<Void>>getArgument(0).accept(null);
            return null;
        }).when(addition).queue(any(), any());
        RestAction<Void> typing = mock(RestAction.class, RETURNS_SELF);
        when(thread.sendTyping()).thenReturn(typing);
        when(threadChannel.sendTyping()).thenReturn(typing);
        doAnswer(call -> {
            call.<Consumer<Void>>getArgument(0).accept(null);
            return null;
        }).when(typing).queue(any(), any());
        when(thread.sendMessage(anyString())).thenAnswer(call -> sendAction(call.getArgument(0)));
    }

    @SuppressWarnings("unchecked")
    SlashCommandInteractionEvent slash(String name, String prompt) {
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        when(event.getName()).thenReturn(name);
        when(event.getUser()).thenReturn(alice);
        when(event.getChannelType()).thenReturn(ChannelType.TEXT);
        MessageChannelUnion channel = mock(MessageChannelUnion.class);
        when(event.getChannel()).thenReturn(channel);
        when(channel.asTextChannel()).thenReturn(parent);
        OptionMapping option = mock(OptionMapping.class);
        when(option.getAsString()).thenReturn(prompt);
        when(event.getOption(name.equals("ask") ? "prompt" : "instructions")).thenReturn(option);
        InteractionHook hook = mock(InteractionHook.class);
        when(event.getHook()).thenReturn(hook);
        when(hook.editOriginal(anyString())).thenAnswer(call -> {
            WebhookMessageEditAction<Message> edit = mock(WebhookMessageEditAction.class, RETURNS_SELF);
            when(edit.submit()).thenReturn(CompletableFuture.completedFuture(mock(Message.class)));
            return edit;
        });
        ReplyCallbackAction defer = mock(ReplyCallbackAction.class);
        when(event.deferReply()).thenReturn(defer);
        doAnswer(call -> {
            call.<Consumer<InteractionHook>>getArgument(0).accept(hook);
            return null;
        }).when(defer).queue(any());
        when(event.reply(anyString())).thenReturn(mock(ReplyCallbackAction.class, RETURNS_SELF));
        return event;
    }

    MessageReceivedEvent message(long userId, String name, String text, boolean mention, boolean replyToCloud) {
        User author = user(userId, name);
        Message incoming = mock(Message.class);
        when(incoming.getAuthor()).thenReturn(author);
        when(incoming.getContentRaw()).thenReturn(text);
        when(incoming.getType()).thenReturn(MessageType.DEFAULT);
        when(incoming.getChannel()).thenReturn(threadChannel);
        Mentions mentions = mock(Mentions.class);
        when(incoming.getMentions()).thenReturn(mentions);
        when(mentions.isMentioned(cloud, Message.MentionType.USER)).thenReturn(mention);
        if (replyToCloud) {
            when(incoming.getType()).thenReturn(MessageType.INLINE_REPLY);
            Message referenced = mock(Message.class);
            when(referenced.getAuthor()).thenReturn(cloud);
            when(incoming.getReferencedMessage()).thenReturn(referenced);
        }
        when(incoming.reply(anyString())).thenAnswer(call -> sendAction(call.getArgument(0)));
        MessageReceivedEvent event = mock(MessageReceivedEvent.class);
        when(event.getAuthor()).thenReturn(author);
        when(event.getMessage()).thenReturn(incoming);
        when(event.getChannel()).thenReturn(threadChannel);
        when(event.getChannelType()).thenReturn(ChannelType.GUILD_PUBLIC_THREAD);
        when(event.getJDA()).thenReturn(jda);
        return event;
    }

    ChannelUpdateArchivedEvent archive(boolean archived) {
        ChannelUnion channel = mock(ChannelUnion.class);
        when(channel.getIdLong()).thenReturn(THREAD_ID);
        return new ChannelUpdateArchivedEvent(jda, 1L, channel, !archived, archived);
    }

    private MessageCreateAction sendAction(String text) {
        MessageCreateAction action = mock(MessageCreateAction.class, RETURNS_SELF);
        when(action.submit()).thenAnswer(call -> {
            delivered.add(text);
            return CompletableFuture.completedFuture(mock(Message.class));
        });
        doAnswer(call -> {
            delivered.add(text);
            call.<Consumer<Message>>getArgument(0).accept(mock(Message.class));
            return null;
        }).when(action).queue(any(), any());
        return action;
    }

    private static User user(long id, String name) {
        User user = mock(User.class);
        when(user.getIdLong()).thenReturn(id);
        when(user.getName()).thenReturn(name);
        return user;
    }

    static final class FakeChatService implements ChatService {
        final List<String> asks = new ArrayList<>();
        final List<List<ConversationTurn>> contexts = new ArrayList<>();
        final ArrayDeque<CompletableFuture<String>> responses = new ArrayDeque<>();

        @Override
        public CompletableFuture<String> ask(String prompt) {
            asks.add(prompt);
            return CompletableFuture.completedFuture("Standalone answer");
        }

        @Override
        public CompletableFuture<String> reply(List<ConversationTurn> history) {
            contexts.add(List.copyOf(history));
            return responses.isEmpty() ? CompletableFuture.completedFuture("Answer " + contexts.size()) : responses.removeFirst();
        }
    }
}
