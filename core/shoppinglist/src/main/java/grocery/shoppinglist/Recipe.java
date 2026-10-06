package grocery.shoppinglist;

import java.util.List;

/** A planned recipe with its ingredients. {@code servings} is null when Mealie has none. */
public record Recipe(String id, String name, Double servings, List<Ingredient> ingredients) {
}
