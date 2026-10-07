package grocery.agent;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * The agent's tools (spec §5.5): domain questions about the household's recipes, answered from
 * Mealie. Read-only for now; planning tools such as {@code replace_meal} follow with the app module.
 */
public class RecipeTools {

    private static final int MAX_RESULTS = 10;

    private final RecipeBook recipes;

    public RecipeTools(RecipeBook recipes) {
        this.recipes = recipes;
    }

    @Tool(description = """
            Lists the tags the household uses on its recipes, such as protein (Fish, Poultry, Red meat, \
            Vegetarian), carbohydrate (Pasta, Rice, Potatoes) and practical tags (Quick, Weekend). \
            Tag names are English.""")
    public List<String> listRecipeTags() {
        return recipes.tagNames();
    }

    @Tool(description = """
            Searches the household's recipes in Mealie, best rated first. Combine free text (matched \
            against name, description and ingredients, in English) with tags; a recipe must carry every \
            tag given. Use tags for kinds of dishes (e.g. Fish for "vis") and text for ingredients \
            (e.g. "salmon"). Returns at most 10 recipes.""")
    public List<RecipeBook.RecipeHit> searchRecipes(
            @ToolParam(required = false, description = "Free text, e.g. an ingredient; empty for any") String text,
            @ToolParam(required = false, description = "Exact tag names from listRecipeTags") List<String> tags) {
        return recipes.search(text == null ? "" : text, tags == null ? List.of() : tags, MAX_RESULTS);
    }

    @Tool(description = "Returns one recipe with servings, ingredients, steps and its link in Mealie.")
    public RecipeBook.RecipeDetail getRecipe(
            @ToolParam(description = "The recipe's slug from searchRecipes") String slug) {
        return recipes.recipe(slug).orElseThrow(() -> new IllegalArgumentException("No recipe with slug " + slug));
    }
}
