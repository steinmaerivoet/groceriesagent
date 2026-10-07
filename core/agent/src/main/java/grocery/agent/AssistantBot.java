package grocery.agent;

import grocery.chat.AccessPolicy;
import grocery.chat.ChatChannel;
import grocery.chat.IncomingText;
import grocery.chat.Message.Notice;

import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * Connects the chat channel (POC 4) to the agent: text addressed to the bot by an allowlisted
 * household member goes to the LLM, the answer goes back to the group. Commands are handled
 * without an LLM call (spec §5.2, INV-07).
 */
public class AssistantBot {

    static final String HELP = """
            Hallo! Ik ben Boodschappenbuddy. Stel me een vraag over jullie recepten in Mealie, \
            bijvoorbeeld "geef mij een recept met vis" of "iets vegetarisch dat snel klaar is". \
            Spreek me aan met een @-vermelding of antwoord op een bericht van mij.""";

    private final AccessPolicy access;
    private final ChatChannel channel;
    private final BiFunction<String, String, String> assistant;
    private final Pattern mention;

    /** {@code assistant} takes a conversation id and the question, and returns the answer. */
    public AssistantBot(AccessPolicy access, ChatChannel channel, BiFunction<String, String, String> assistant,
                        String botUsername) {
        this.access = access;
        this.channel = channel;
        this.assistant = assistant;
        this.mention = Pattern.compile("@" + Pattern.quote(botUsername == null ? "" : botUsername) + "\\b",
                Pattern.CASE_INSENSITIVE);
    }

    public void handle(IncomingText text) {
        if (!access.isHouseholdChat(text.chatId())) {
            return;
        }
        if (!access.isAllowed(text.userId())) {
            reply("Sorry " + text.userName() + ", alleen leden van het huishouden kunnen deze bot gebruiken.");
            return;
        }
        String question = mention.matcher(text.text()).replaceAll("").strip();
        if (text.command()) {
            String command = question.split("\\s+", 2)[0].toLowerCase();
            if (command.equals("/start") || command.equals("/help")) {
                reply(HELP);
            } else {
                reply("Dat commando ken ik (nog) niet. Probeer /help.");
            }
            return;
        }
        if (question.isEmpty()) {
            reply(HELP);
            return;
        }
        String answer;
        try {
            answer = assistant.apply("chat-" + text.chatId(), question);
        } catch (RuntimeException e) {
            // Spec §7: when the LLM is unavailable only free text stops working.
            System.err.println("Agent failed: " + e);
            answer = "Sorry, ik kan je vraag nu niet beantwoorden (" + e.getClass().getSimpleName()
                    + "). Probeer het straks opnieuw.";
        }
        reply(answer);
    }

    private void reply(String text) {
        channel.post(new Notice(text, false), null);
    }
}
