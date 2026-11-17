# ☁️🐶Cloud.

Cloud is a Discord AI chatbot powered by Google Gemini. It answers standalone questions and participates in shared thread conversations, keeping track of recent messages and the people speaking.

## Commands

| Command | Purpose |
| --- | --- |
| `/cloud` | Learn about Cloud, view command help, and read its development status. |
| `/ask prompt: ...` | Ask a standalone question without starting a conversation thread. |
| `/conversation instructions: ...` | Create a public thread for a conversation with Cloud and other server members. An optional `thread_name` names the thread. |

## Shared conversations

Start a conversation with instructions that describe what you want to discuss and how Cloud should respond. Cloud creates a public thread, adds the person who started it, and posts an initial answer.

For example:

```text
/conversation instructions: Help us brainstorm names for a community garden. Answer in Spanish. thread_name: Garden ideas
```

Within that thread, Cloud can:

- **Keep the initial instructions in context.** The opening instructions remain available throughout the active session, even as older messages leave recent history.
- **Follow multiple participants.** Human messages include author attribution so Cloud can distinguish speakers.
- **Use ordinary chat as context.** Human messages in the conversation thread are recorded even when Cloud is not addressed.
- **Respond when addressed.** Mention Cloud or reply directly to one of its messages to request an answer. Replies work with the reply mention disabled.
- **Stay aware of the discussion.** Ask Cloud to summarize or build on recent messages from other participants.

## Response behavior

- **Typing indication:** Cloud refreshes its typing indicator while preparing and sending conversation responses, including the initial answer in a new thread.
- **Independent questions:** Multiple users can submit `/ask` requests while other answers are still being generated. Each answer stays attached to its own command interaction.
- **Conversation coordination:** Cloud prepares one AI response at a time in each conversation thread. Participants can continue chatting while it works.
- **Cooldowns:** A shared five-second per-user cooldown helps limit the frequency of AI requests.
- **Long answers:** Responses are split into ordered Discord messages, with outgoing mentions disabled to avoid accidental pings.
- **Failure handling:** Cloud provides error responses for generation failures and handles timeouts, API rate limits, and safety-blocked answers.

## Conversation lifetime and limits

- Conversation memory is stored in memory and is lost when the bot restarts.
- Each session keeps its initial instructions plus up to seven subsequent human or bot turns. Older subsequent turns are pruned.
- Sessions expire after 45 minutes of inactivity. Archiving a thread retires its session; unarchiving does not restore the previous context.
- AI output is limited to three message chunks, with longer responses truncated.
- Typing visibility and response delivery depend on Discord, Gemini, and network availability.

See [CHANGELOG.md](CHANGELOG.md) for the project's release history.
