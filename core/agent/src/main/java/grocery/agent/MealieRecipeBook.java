package grocery.agent;

import com.fasterxml.jackson.databind.JsonNode;
import grocery.mealie.MealieClient;
import grocery.mealie.MealieException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/** {@link RecipeBook} over the Mealie REST API (POC 1's client). */
public class MealieRecipeBook implements RecipeBook {

    private final MealieClient mealie;
    private volatile String groupSlug;

    public MealieRecipeBook(MealieClient mealie) {
        this.mealie = mealie;
    }

    @Override
    public List<String> tagNames() {
        return mealie.tags().stream().map(t -> t.path("name").asText()).sorted().toList();
    }

    @Override
    public List<RecipeHit> search(String text, List<String> tagNames, int limit) {
        Map<String, String> idsByName = mealie.tags().stream()
                .collect(Collectors.toMap(t -> t.path("name").asText().toLowerCase(), t -> t.path("id").asText(),
                        (a, b) -> a));
        List<String> tagIds = new ArrayList<>();
        for (String name : tagNames) {
            String id = idsByName.get(name.strip().toLowerCase());
            if (id == null) {
                return List.of(); // the model asked for a tag the household doesn't use
            }
            tagIds.add(id);
        }
        return mealie.searchRecipes(text, tagIds, limit).stream()
                .map(r -> new RecipeHit(r.path("name").asText(), r.path("slug").asText(), text(r, "description"),
                        names(r.path("tags")), r.path("rating").isNumber() ? r.path("rating").asInt() : null,
                        text(r, "totalTime")))
                .toList();
    }

    @Override
    public Optional<RecipeDetail> recipe(String slug) {
        JsonNode r;
        try {
            r = mealie.recipe(slug);
        } catch (MealieException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
        List<String> ingredients = stream(r.path("recipeIngredient")).map(MealieRecipeBook::ingredient)
                .filter(s -> !s.isBlank()).toList();
        List<String> steps = stream(r.path("recipeInstructions")).map(s -> s.path("text").asText())
                .filter(s -> !s.isBlank()).toList();
        String url = mealie.baseUrl().resolve("/g/" + groupSlug() + "/r/" + r.path("slug").asText()).toString();
        return Optional.of(new RecipeDetail(r.path("name").asText(), r.path("slug").asText(), text(r, "description"),
                names(r.path("tags")), r.path("recipeServings").asText(null), text(r, "totalTime"), ingredients,
                steps, url));
    }

    private String groupSlug() {
        if (groupSlug == null) {
            groupSlug = mealie.group().path("slug").asText("home");
        }
        return groupSlug;
    }

    /** Mealie renders each ingredient as "2 cloves garlic, crushed" in {@code display}. */
    private static String ingredient(JsonNode i) {
        for (String field : List.of("display", "originalText", "note")) {
            String value = i.path(field).asText("");
            if (!value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    private static List<String> names(JsonNode array) {
        return stream(array).map(t -> t.path("name").asText()).toList();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() || value.asText().isBlank() ? null : value.asText();
    }

    private static java.util.stream.Stream<JsonNode> stream(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(Function.identity());
    }
}
