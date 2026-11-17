package dev.romulus_lanceues.discord.commands;

import dev.romulus_lanceues.ai.ChatService;
import dev.romulus_lanceues.discord.SlashCommandHandler;
import dev.romulus_lanceues.discord.TypingIndicator;
import dev.romulus_lanceues.memory.ConversationManager;
import dev.romulus_lanceues.memory.ConversationSession;
import dev.romulus_lanceues.memory.ConversationTurn;
import dev.romulus_lanceues.util.MessageSplitter;
import dev.romulus_lanceues.util.RateLimiter;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

public class ConversationCommand implements SlashCommandHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ConversationCommand.class);

    private static final String NAME = "conversation";
    private static final String DESCRIPTION = "Have a conversation with Cloud.";
    private static final String OPTION_INITIAL_PROMPT = "instructions";
    private static final String OPTION_THREAD_NAME = "thread_name";

    // Discord message limit is 2000 characters. I truncate the preview to keep the total message well within bounds.
    static final int MAX_PROMPT_PREVIEW_LENGTH = 1800;
    static final int MAX_THREAD_NAME_LENGTH = 90;

    private final RateLimiter rateLimiter;
    private final ConversationManager conversationManager;
    private final ChatService chatService;
    private final ScheduledExecutorService scheduler;

    public ConversationCommand(RateLimiter rateLimiter, ConversationManager conversationManager,
                               ChatService chatService, ScheduledExecutorService scheduler) {
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "Rate Limiter should not be null");
        this.conversationManager = Objects.requireNonNull(conversationManager, "Conversation Manager should not be null");
        this.chatService = Objects.requireNonNull(chatService, "Chat Service should not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "Scheduler should not be null");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public SlashCommandData commandData() {
        return Commands.slash(NAME, DESCRIPTION)
                .addOption(OptionType.STRING, OPTION_INITIAL_PROMPT, "The instruction you want Cloud to know throughout the whole conversation", true)
                .addOption(OptionType.STRING, OPTION_THREAD_NAME, "What should be this conversation's name?", false);
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        if(event.getChannelType() != ChannelType.TEXT) {
            event.reply("Please start a conversation in a server text channel!")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        OptionMapping promptOption = event.getOption(OPTION_INITIAL_PROMPT);
        OptionMapping threadOption = event.getOption(OPTION_THREAD_NAME);

        //Configure initial prompt
        if (promptOption == null || promptOption.getAsString().strip().isEmpty()) {
            event.reply("Please provide a non-empty prompt.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        //Configure thread name
        String suppliedName = threadOption == null ? "" : threadOption.getAsString().strip();
        String threadName = suppliedName.isBlank() ? "Cloud Conversation Thread" : suppliedName ;

        if(threadName.length() > MAX_THREAD_NAME_LENGTH) {
            event.reply("Please provide a thread name within " + MAX_THREAD_NAME_LENGTH + " characters.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        //Check the user's rate limit
        long userId = event.getUser().getIdLong();
        Optional<Duration> cooldown = rateLimiter.checkCooldown(userId);

        if(cooldown.isPresent()){
            long remainingSeconds = Math.max(1, (long) Math.ceil(cooldown.get().toMillis() / 1000.0));
            event.reply(String.format("Please wait %d seconds before asking another question.", remainingSeconds))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        //Create server thread
        event.deferReply().queue( hook -> {
            event.getChannel().asTextChannel()
                            .createThreadChannel(threadName)
                            .queue(thread -> {
                                thread.addThreadMember(event.getUser()).queue( success -> {
                                    hook.editOriginal("Your conversation has been created!" + thread.getAsMention()).queue();


                                    ConversationSession createdSession = registerConversation(thread.getIdLong(), promptOption.getAsString(), event.getUser().getName());
                                    LOG.info("Conversation {} has been created!",createdSession);

                                    TypingIndicator typing = new TypingIndicator(thread, conversationManager, createdSession, scheduler);
                                    try {
                                        typing.start();
                                        CompletableFuture<Void> workflow = chatService.reply(createdSession.getActiveTurns())
                                                .thenCompose(result -> handleDeliveryAndRecord(result, thread, createdSession));

                                        workflow.whenComplete((ignored, failure) -> {
                                            try{
                                                if(failure != null) {
                                                    LOG.error("An error occurred while sending the response to the user", failure);
                                                    hook.editOriginal("An error occurred while sending the response to the user")
                                                            .setAllowedMentions(Collections.emptyList())
                                                            .queue();
                                                }
                                            } finally {
                                                try {
                                                    typing.close();
                                                } finally {
                                                    createdSession.releaseLock();
                                                }
                                            }

                                        });
                                    } catch (Exception e) {
                                        try{
                                            LOG.error("Failed to process the user after the conversation has been created!", e);
                                            hook.editOriginal("An error occurred while sending the response to the user")
                                                    .setAllowedMentions(Collections.emptyList())
                                                    .queue();
                                        }
                                        finally {
                                            try {
                                                typing.close();
                                            } finally {
                                                createdSession.releaseLock();
                                            }
                                        }
                                    }

                                }, ignored -> {
                                    hook.editOriginal("The thread was created, but I couldn't add you: "
                                            + thread.getAsMention()).queue();
                                    LOG.error(ignored.getMessage(), ignored);
                                });

                            }, failure -> {
                                LOG.error(failure.getMessage(), failure);
                                hook.editOriginal("Opps, something went wrong!").queue();
                            });
        });

    }

    private ConversationSession registerConversation (long threadId, String initialPrompt, String initialAuthor){
       return  conversationManager.createSession(
               threadId, initialPrompt, initialAuthor
       );
    }

    private boolean canRespond(ConversationSession session) {
        synchronized (session) {
            return conversationManager.getSession(session.getThreadId()).orElse(null) == session
                    && session.verifyStateEligibility(ConversationManager.TIME_TO_LIVE_DURATION);
        }
    }

    private CompletableFuture<Void> handleDeliveryAndRecord(String result, ThreadChannel thread, ConversationSession session){
        if (!canRespond(session)) return CompletableFuture.completedFuture(null);

        List<String> chunks =  MessageSplitter.split(result, MAX_PROMPT_PREVIEW_LENGTH);

        if (chunks.isEmpty()) {
            IllegalArgumentException failure = new IllegalArgumentException("The result is empty");
            LOG.error(failure.getMessage());
            return CompletableFuture.failedFuture(failure);
        }

            CompletableFuture<?> delivery = CompletableFuture.completedFuture(null);

            for (String chunk : chunks) {
                delivery = delivery.thenCompose(ignored -> {
                    synchronized (session) {
                        if (!canRespond(session)) return CompletableFuture.completedFuture(null);
                        return thread.sendMessage(chunk)
                            .setAllowedMentions(Collections.emptyList())
                            .setCheck(() -> canRespond(session))
                            .submit();
                    }
                });
            }

            return delivery.thenRun(() -> {
                synchronized (session) {
                    if (!canRespond(session)) return;
                    String deliveredText = String.join("\n", chunks);
                    conversationManager.insertSessionTurn(thread.getIdLong(), ConversationTurn.bot(deliveredText));
                }
            });

    }
}
