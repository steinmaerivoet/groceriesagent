package grocery.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** A few recipes in memory; records every search so tests can check what the agent asked for. */
class FakeRecipeBook implements RecipeBook {

    record Search(String text, List<String> tags) {
    }

    static final RecipeHit SALMON = new RecipeHit("Oven-baked salmon with dill potatoes", "oven-baked-salmon",
            "Salmon fillets roasted on a bed of potatoes.", List.of("Fish", "Potatoes"), 5, "40 minutes");
    static final RecipeHit CURRY = new RecipeHit("Chickpea curry", "chickpea-curry", "Mild curry.",
            List.of("Vegetarian", "Legumes", "Quick"), 4, "25 minutes");

    final List<Search> searches = new ArrayList<>();

    @Override
    public List<String> tagNames() {
        return List.of("Fish", "Legumes", "Potatoes", "Quick", "Vegetarian");
    }

    @Override
    public List<RecipeHit> search(String text, List<String> tags, int limit) {
        searches.add(new Search(text, tags));
        return List.of(SALMON, CURRY).stream()
                .filter(r -> r.tags().containsAll(tags))
                .filter(r -> text.isBlank() || r.name().toLowerCase().contains(text.toLowerCase()))
                .limit(limit)
                .toList();
    }

    @Override
    public Optional<RecipeDetail> recipe(String slug) {
        return slug.equals(SALMON.slug())
                ? Optional.of(new RecipeDetail(SALMON.name(), SALMON.slug(), SALMON.description(), SALMON.tags(), "4",
                SALMON.totalTime(), List.of("4 salmon fillets", "800 g potatoes"), List.of("Roast everything."),
                "http://localhost:9925/g/home/r/oven-baked-salmon"))
                : Optional.empty();
    }
}
