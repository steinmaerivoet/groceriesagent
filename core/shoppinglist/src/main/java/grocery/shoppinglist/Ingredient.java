package grocery.shoppinglist;

import grocery.contracts.PurchasePolicy;

/**
 * A recipe ingredient as read from Mealie. {@code foodId} is null for an unparsed ingredient.
 *
 * @param policyOverride the Food's own {@code groceries.purchasePolicy} extra, or null
 * @param quantity       null when the recipe gives none ("salt")
 */
public record Ingredient(
        String foodId,
        String foodName,
        String labelId,
        String labelName,
        PurchasePolicy policyOverride,
        Double quantity,
        String unitId,
        String unitName,
        String originalText) {

    public boolean isParsed() {
        return foodId != null;
    }
}
