package grocery.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The whole Spring application with Bedrock and Mealie replaced: question → tool call → answer. */
class AgentFlowTest {

    private final ScriptedBedrock bedrock = new ScriptedBedrock();
    private final FakeRecipeBook recipes = new FakeRecipeBook();
    private ConfigurableApplicationContext context;

    @Configuration
    static class Fakes {
        static ScriptedBedrock bedrock;
        static FakeRecipeBook recipes;

        @Bean
        BedrockRuntimeClient bedrockRuntimeClient() {
            return bedrock;
        }

        @Bean
        RecipeBook recipeBook() {
            return recipes;
        }
    }

    private GroceryAssistant start() {
        Fakes.bedrock = bedrock;
        Fakes.recipes = recipes;
        context = new SpringApplicationBuilder(Fakes.class, AgentApplication.class) // fakes first, so the real beans back off
                .properties(Map.of(
                        "grocery.agent.channel", "none",
                        "AWS_ACCESS_KEY_ID", "test",
                        "AWS_SECRET_ACCESS_KEY", "test",
                        "BEDROCK_MODEL_ID", "test-model"))
                .run();
        return context.getBean(GroceryAssistant.class);
    }

    @AfterEach
    void stop() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void answersAFishQuestionFromMealieRecipes() {
        bedrock.thenToolUse("searchRecipes", Document.mapBuilder()
                        .putList("tags", List.of(Document.fromString("Fish"))).build())
                .thenText("Probeer de Oven-baked salmon with dill potatoes!");

        String answer = start().answer("chat-1", "Geef mij een recept met vis");

        assertThat(answer).isEqualTo("Probeer de Oven-baked salmon with dill potatoes!");
        assertThat(recipes.searches).containsExactly(new FakeRecipeBook.Search("", List.of("Fish")));

        ConverseRequest first = bedrock.requests.getFirst();
        assertThat(first.modelId()).isEqualTo("test-model");
        assertThat(first.system().getFirst().text()).contains("Boodschappenbuddy");
        assertThat(first.toolConfig().tools()).extracting(t -> t.toolSpec().name())
                .containsExactlyInAnyOrder("listRecipeTags", "searchRecipes", "getRecipe");

        // The tool result Bedrock sees in the second request is the salmon recipe only.
        String toolResult = bedrock.requests.get(1).messages().stream()
                .flatMap(m -> m.content().stream())
                .filter(c -> c.type() == ContentBlock.Type.TOOL_RESULT)
                .map(c -> c.toolResult().content().toString())
                .findFirst().orElseThrow();
        assertThat(toolResult).contains("oven-baked-salmon").doesNotContain("chickpea-curry");
    }

    @Test
    void remembersTheConversationPerChat() {
        bedrock.thenText("Ik heb een zalmrecept.").thenText("Hij duurt 40 minuten.").thenText("Hallo!");
        GroceryAssistant assistant = start();

        assistant.answer("chat-1", "Heb je iets met vis?");
        assistant.answer("chat-1", "Hoe lang duurt dat?");
        assistant.answer("chat-2", "Hallo");

        assertThat(userTexts(bedrock.requests.get(1))).containsExactly("Heb je iets met vis?", "Hoe lang duurt dat?");
        assertThat(userTexts(bedrock.requests.get(2))).containsExactly("Hallo");
    }

    private static List<String> userTexts(ConverseRequest request) {
        return request.messages().stream()
                .filter(m -> m.roleAsString().equals("user"))
                .flatMap(m -> m.content().stream())
                .filter(c -> c.text() != null)
                .map(ContentBlock::text)
                .toList();
    }
}
