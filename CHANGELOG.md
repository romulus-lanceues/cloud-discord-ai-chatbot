# Changelog

Notable changes to Cloud are documented here. Changes awaiting release are listed under **Unreleased**.

## [1.0.0] Initial Release

### Added

- Multi-user conversations through `/conversation`, with required `instructions` and an optional `thread_name`.
- Public thread creation, automatic addition of the invoking user, and an initial Gemini response inside the thread.
- Conversation memory that preserves the initial instructions and their author, followed by up to seven recent turns.
- Author attribution and multi-turn Gemini requests so Cloud can distinguish participants and retain conversation context.
- Passive recording of human messages in managed threads without generating an AI response for every message.
- Responses when Cloud is mentioned or directly replied to, including replies with mentions disabled. Missing reply targets are retrieved asynchronously.
- Typing indicators for initial thread responses and subsequent conversation replies, refreshed every five seconds while generation and delivery are in progress.
- Per-thread generation guards to prevent overlapping AI responses, including while the initial response is pending.
- Conversation inactivity expiration after 45 minutes, scheduled cleanup, and session removal when a thread is archived. Unarchiving does not restore retired context.
- Checks that suppress late responses, queued messages, and typing after a conversation session is retired or replaced.

### Changed

- Extended the shared per-user cooldown to conversation commands and addressed thread messages.
- Delivered conversation responses sequentially, disabled outgoing mentions, and recorded the delivered text after all chunks succeed.
- Coordinated cleanup of conversation state and typing refresh tasks through a shared scheduler and application shutdown handling.

### Fixed

- `/ask` now waits for the original response edit and each subsequent chunk to succeed before sending the next chunk. Delivery failures stop the remaining sends.
- `/ask` now handles generation errors thrown before a response future is returned, as well as failed futures and missing or blank responses.
- Failure-notification errors are logged without causing another notification attempt or interrupting cleanup.
- Protected conversation guard ownership across generation, delivery, recording, and failure paths.

### Tests

- Added unit coverage for conversation turns, pinned history, pruning, expiration, session management, and multi-turn Gemini request conversion.
- Added command and listener tests for mentions, direct replies, passive recording, sequential delivery, failures, concurrency guards, and archive cleanup.
- Added direct typing-indicator tests for refresh scheduling, concurrent start/close behavior, cancellation, retries, pending-request limits, and scheduler ownership.
- Expanded registration integration tests to verify exported command names, routing, response submission, successful conversation creation, and shared cooldowns.
- Added conversation flow integration tests covering context sharing between commands and thread listeners, multiple participants, initial-response guard ownership, and archive/unarchive behavior.

### Known limitations

- Conversation history is stored in memory and is lost when the bot restarts. Older turns are pruned while the initial instructions remain pinned.
- AI responses are limited to three Discord message chunks; longer output is truncated.
- Typing visibility depends on Discord and network availability. Messages already dispatched to Discord cannot be recalled after a thread is archived.
- Automated tests use fake or mocked external dependencies; live Discord behavior requires separate verification.

## [0.1.0]

Existing tagged baseline.

### Added

- Initial Java 21 Discord bot using JDA and the Google Gen AI SDK.
- `/cloud` command with information, help, and development-status responses.
- `/ask` command for standalone Gemini questions.
- Environment-based configuration for Discord credentials, Gemini credentials, model selection, generation settings, and system instructions.
- Per-user cooldowns, asynchronous Gemini requests, request timeouts, and friendly API error responses.
- Response splitting and suppression of outgoing mentions for AI answers.
- Gradle build configuration, runnable Shadow JAR packaging, and automated tests for the initial commands, configuration, AI service, and utilities.
