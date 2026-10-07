package grocery.shoppinglist;

import grocery.contracts.PurchasePolicy;

import java.util.Map;

/**
 * A recipe ingredient as read from Mealie. {@code foodId} is null for an unparsed ingredient.
 *
 * @param policyOverride the Food's own {@code groceries.purchasePolicy} extra, or null
 * @param gramsPer       the Food's {@code groceries.gramsPer} extra: grams per unit name, with
 *                       {@code piece} for "no unit" (e.g. tablespoon → 16 for peanut butter)
 * @param quantity       null when the recipe gives none ("salt")
 */
public record Ingredient(
        String foodId,
        String foodName,
        String labelId,
        String labelName,
        PurchasePolicy policyOverride,
        Map<String, Double> gramsPer,
        Double quantity,
        String unitId,
        String unitName,
        String originalText) {

    public Ingredient {
        gramsPer = gramsPer == null ? Map.of() : Map.copyOf(gramsPer);
    }

    public boolean isParsed() {
        return foodId != null;
    }
}
