package grocery.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Free-text conversation with the household (spec §5.5): one LLM loop over {@link RecipeTools}.
 * Each chat keeps its last few messages so follow-ups ("and something quicker?") work; that
 * memory is in-process only, so a restart simply starts a fresh conversation.
 */
public class GroceryAssistant {

    static final String SYSTEM_PROMPT = """
            You are Boodschappenbuddy, the household's grocery and meal-planning assistant in their \
            Telegram group. Answer in the language of the question (usually Dutch/Flemish), briefly and \
            in plain text without Markdown, because Telegram shows it as is.

            Only recommend recipes that the tools return: they come from the household's own Mealie \
            recipe collection. Recipe names, tags and ingredients in Mealie are in English, so translate \
            the question first (vis -> Fish, kip -> Poultry, vegetarisch -> Vegetarian, snel -> Quick). \
            If nothing matches, say so and suggest a nearby alternative from the collection. Mention a \
            recipe's Mealie link when you give its details.""";

    private static final int REMEMBERED_MESSAGES = 20;

    private final ChatClient chat;

    public GroceryAssistant(ChatModel model, RecipeTools tools) {
        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(REMEMBERED_MESSAGES).build();
        this.chat = ChatClient.builder(model)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultTools(tools)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .build();
    }

    /** Answers one message; {@code conversationId} separates chats (e.g. the Telegram chat id). */
    public String answer(String conversationId, String text) {
        String answer = chat.prompt()
                .user(text)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
        return answer == null || answer.isBlank() ? "Sorry, daar heb ik geen antwoord op." : answer.strip();
    }
}
