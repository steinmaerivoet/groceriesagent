package grocery.agent;

import java.util.List;
import java.util.Optional;

/**
 * The read-only recipe operations the agent may use. Backed by Mealie; tests use a fixed list.
 * Tag names are the household's own Mealie tags (Fish, Vegetarian, Quick, …).
 */
public interface RecipeBook {

    List<String> tagNames();

    /** Best-rated recipes matching the text and carrying every tag; unknown tags match nothing. */
    List<RecipeHit> search(String text, List<String> tagNames, int limit);

    Optional<RecipeDetail> recipe(String slug);

    record RecipeHit(String name, String slug, String description, List<String> tags, Integer rating,
                     String totalTime) {
    }

    record RecipeDetail(String name, String slug, String description, List<String> tags, String servings,
                        String totalTime, List<String> ingredients, List<String> steps, String url) {
    }
}
