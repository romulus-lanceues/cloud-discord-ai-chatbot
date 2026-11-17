package dev.romulus_lanceues.discord.commands;

import dev.romulus_lanceues.ai.ChatService;
import dev.romulus_lanceues.discord.SlashCommandHandler;
import dev.romulus_lanceues.util.MessageSplitter;
import dev.romulus_lanceues.util.RateLimiter;
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

public class AskCommand implements SlashCommandHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AskCommand.class);

    private static final String NAME = "ask";
    private static final String DESCRIPTION = "Ask Cloud a question or prompt.";
    private static final String OPTION_PROMPT = "prompt";
    static final String GENERATION_FAILED = "I encountered an unexpected issue while generating a response. Please try again later.";

    private final ChatService chatService;
    private final RateLimiter rateLimiter;

    public AskCommand(ChatService chatService, RateLimiter rateLimiter) {
        this.chatService = Objects.requireNonNull(chatService, "chatService must not be null");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter must not be null");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public SlashCommandData commandData() {
        return Commands.slash(NAME, DESCRIPTION)
                .addOption(OptionType.STRING, OPTION_PROMPT, "The question or prompt to send to Cloud.", true);
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        OptionMapping promptOption = event.getOption(OPTION_PROMPT);
        if (promptOption == null || promptOption.getAsString().strip().isEmpty()) {
            event.reply("Please provide a non-empty prompt.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        long userId = event.getUser().getIdLong();
        Optional<Duration> cooldown = rateLimiter.checkCooldown(userId);
        if (cooldown.isPresent()) {
            long remainingSeconds = Math.max(1, (long) Math.ceil(cooldown.get().toMillis() / 1000.0));
            event.reply(String.format("⏳ Please wait %d seconds before asking another question.", remainingSeconds))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        event.deferReply().queue();

        String prompt = promptOption.getAsString().strip();
        try {
            chatService.ask(prompt).whenComplete((result, throwable) -> {
                if (throwable != null) {
                    reportGenerationFailure(event, userId, throwable);
                    return;
                }
                if (result == null || result.isBlank()) {
                    reportGenerationFailure(event, userId, new IllegalStateException("Generated response was empty"));
                    return;
                }

                deliverResponse(event, MessageSplitter.split(result, 2000))
                        .whenComplete((ignored, failure) -> {
                            if (failure != null) {
                                LOG.error("Failed to deliver /ask response for user {}; delivery may be partial", userId, failure);
                            }
                        });
            });
        } catch (RuntimeException failure) {
            // The service can throw before it returns a future.
            reportGenerationFailure(event, userId, failure);
        }
    }

    private CompletableFuture<Void> deliverResponse(SlashCommandInteractionEvent event, List<String> chunks) {
        // Starting inside thenCompose also captures synchronous action creation/submission failures.
        CompletableFuture<?> delivery = CompletableFuture.completedFuture(null)
                .thenCompose(ignored -> event.getHook().editOriginal(chunks.getFirst())
                        .setAllowedMentions(Collections.emptyList())
                        .submit());
        for (int i = 1; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            delivery = delivery.thenCompose(ignored -> event.getHook().sendMessage(chunk)
                    .setAllowedMentions(Collections.emptyList())
                    .submit());
        }
        return delivery.thenRun(() -> {});
    }

    private void reportGenerationFailure(SlashCommandInteractionEvent event, long userId, Throwable failure) {
        LOG.error("Failed to process /ask request for user {}", userId, failure);
        try {
            event.getHook().editOriginal(GENERATION_FAILED)
                    .setAllowedMentions(Collections.emptyList())
                    .queue(ignored -> {}, notificationFailure ->
                            LOG.warn("Could not send /ask failure notification for user {}", userId, notificationFailure));
        } catch (RuntimeException notificationFailure) {
            LOG.warn("Could not submit /ask failure notification for user {}", userId, notificationFailure);
        }
    }
}
