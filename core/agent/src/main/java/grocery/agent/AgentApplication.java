package grocery.agent;

import grocery.mealie.MealieClient;
import grocery.mealie.MealieConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * POC 5 demo: the Telegram bot backed by a Spring AI agent on Amazon Bedrock that answers from
 * Mealie. Run from {@code core/}:
 * <pre>
 *   ./gradlew -q --console=plain :agent:run --args=console   # chat in the terminal
 *   ./gradlew -q :agent:run                                  # in the Telegram group (needs TELEGRAM_* )
 * </pre>
 * Needs Mealie ({@code MEALIE_API_TOKEN}) and AWS credentials with Bedrock access; see
 * {@code core/README.md}.
 */
@SpringBootApplication
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }

    @Bean
    @ConditionalOnMissingBean
    RecipeBook recipeBook() {
        return new MealieRecipeBook(new MealieClient(MealieConfig.fromEnvironment()));
    }

    @Bean
    GroceryAssistant groceryAssistant(ChatModel model, RecipeBook recipes) {
        return new GroceryAssistant(model, new RecipeTools(recipes));
    }
}
