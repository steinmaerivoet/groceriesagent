package grocery.contracts;

import java.util.List;

/**
 * One aggregated requirement for a Food. A Food needed in incompatible units (e.g. grams and
 * pieces) yields one item per unit dimension.
 *
 * @param quantity the scaled and summed quantity, or null when no recipe gave one ("salt")
 * @param unitId   Mealie Unit id, or null for a plain count
 * @param recipes  names of the recipes that need it
 */
public record RequiredItem(
        String foodId,
        String foodName,
        String labelId,
        String labelName,
        PurchasePolicy policy,
        Double quantity,
        String unitId,
        String unitName,
        List<String> recipes) {
}
