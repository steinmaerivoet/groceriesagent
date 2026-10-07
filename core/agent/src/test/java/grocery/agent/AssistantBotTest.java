package grocery.agent;

import grocery.chat.AccessPolicy;
import grocery.chat.ChatChannel;
import grocery.chat.IncomingText;
import grocery.chat.Interaction;
import grocery.chat.Message;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantBotTest {

    private static final long GROUP = -100L;
    private static final long STEIN = 1L;

    private final List<String> posted = new ArrayList<>();
    private final List<String> questions = new ArrayList<>();
    private final ChatChannel channel = new ChatChannel() {
        @Override
        public String post(Message message, Interaction interaction) {
            posted.add(((Message.Notice) message).text());
            return "m" + posted.size();
        }

        @Override
        public void refresh(Interaction interaction) {
        }

        @Override
        public void acknowledge(String pressId, String text) {
        }
    };

    private AssistantBot bot(RuntimeException failure) {
        return new AssistantBot(new AccessPolicy(GROUP, Set.of(STEIN)), channel, (conversation, question) -> {
            questions.add(conversation + ": " + question);
            if (failure != null) {
                throw failure;
            }
            return "Probeer de zalm.";
        }, "boodschappen_buddy_bot");
    }

    @Test
    void sendsTheQuestionWithoutTheMentionToTheAgent() {
        bot(null).handle(new IncomingText(GROUP, STEIN, "Stein", "@boodschappen_buddy_bot geef mij een recept met vis", false));

        assertThat(questions).containsExactly("chat--100: geef mij een recept met vis");
        assertThat(posted).containsExactly("Probeer de zalm.");
    }

    @Test
    void answersCommandsWithoutTheAgent() {
        bot(null).handle(new IncomingText(GROUP, STEIN, "Stein", "/help@boodschappen_buddy_bot", true));

        assertThat(questions).isEmpty();
        assertThat(posted).containsExactly(AssistantBot.HELP);
    }

    @Test
    void refusesMembersOutsideTheAllowlistAndIgnoresOtherChats() {
        bot(null).handle(new IncomingText(GROUP, 99L, "Guest", "@boodschappen_buddy_bot vis?", false));
        bot(null).handle(new IncomingText(-200L, STEIN, "Stein", "@boodschappen_buddy_bot vis?", false));

        assertThat(questions).isEmpty();
        assertThat(posted).hasSize(1).first().asString().startsWith("Sorry Guest");
    }

    @Test
    void saysSoWhenTheModelIsUnavailable() {
        bot(new IllegalStateException("Bedrock down")).handle(
                new IncomingText(GROUP, STEIN, "Stein", "@boodschappen_buddy_bot vis?", false));

        assertThat(posted).hasSize(1).first().asString().contains("kan je vraag nu niet beantwoorden");
    }
}
