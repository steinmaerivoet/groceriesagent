package grocery.shoppinglist;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import grocery.contracts.Json;
import grocery.contracts.PurchasePolicy;
import grocery.mealie.MealieClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads planned recipes and their ingredients from Mealie. */
public final class MealieRecipes {

    private MealieRecipes() {
    }

    public static List<Recipe> load(MealieClient mealie, List<String> recipeIds) {
        return recipeIds.stream().map(id -> toRecipe(mealie.recipe(id))).toList();
    }

    static Recipe toRecipe(JsonNode json) {
        List<Ingredient> ingredients = new ArrayList<>();
        for (JsonNode i : json.path("recipeIngredient")) {
            JsonNode food = i.path("food");
            JsonNode unit = i.path("unit");
            JsonNode quantity = i.path("quantity");
            String text = firstNonBlank(i.path("originalText").asText(""), i.path("display").asText(""), i.path("note").asText(""));
            ingredients.add(new Ingredient(
                    food.isObject() ? food.path("id").asText() : null,
                    food.isObject() ? food.path("name").asText() : null,
                    food.path("label").isObject() ? food.path("label").path("id").asText() : null,
                    food.path("label").isObject() ? food.path("label").path("name").asText() : null,
                    policyOverride(food.path("extras")),
                    gramsPer(food.path("extras")),
                    quantity.isNumber() && quantity.asDouble() > 0 ? quantity.asDouble() : null,
                    unit.isObject() ? unit.path("id").asText() : null,
                    unit.isObject() ? unit.path("name").asText() : null,
                    text));
        }
        JsonNode servings = json.path("recipeServings");
        return new Recipe(json.path("id").asText(), json.path("name").asText(),
                servings.isNumber() && servings.asDouble() > 0 ? servings.asDouble() : null, ingredients);
    }

    /** Mealie stores extras as flat strings, so {@code groceries} holds a JSON document as text. */
    static PurchasePolicy policyOverride(JsonNode extras) {
        JsonNode groceries = GroceriesExtra.parse(extras);
        String policy = groceries.path("purchasePolicy").asText(null);
        return policy == null ? null : PurchasePolicy.valueOf(policy);
    }

    static Map<String, Double> gramsPer(JsonNode extras) {
        Map<String, Double> grams = new HashMap<>();
        GroceriesExtra.parse(extras).path("gramsPer").properties()
                .forEach(e -> grams.put(e.getKey(), e.getValue().asDouble()));
        return grams;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (!v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    /** Reads and writes the {@code groceries} namespace in Mealie extras (spec §3.3). */
    static final class GroceriesExtra {

        static final String KEY = "groceries";

        private GroceriesExtra() {
        }

        static JsonNode parse(JsonNode extras) {
            String raw = extras.path(KEY).asText("");
            if (raw.isBlank()) {
                return Json.MAPPER.createObjectNode();
            }
            try {
                return Json.MAPPER.readTree(raw);
            } catch (JsonProcessingException e) {
                return Json.MAPPER.createObjectNode();
            }
        }
    }
}
