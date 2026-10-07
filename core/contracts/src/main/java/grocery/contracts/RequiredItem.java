package grocery.contracts;

import java.util.ArrayList;
import java.util.List;

/**
 * One aggregated requirement for a Food: always one item per Food. Amounts in other units are
 * converted into {@code unitName} where the Food's weight per unit allows it; the rest are kept
 * in {@code otherAmounts}.
 *
 * @param quantity     the scaled and summed quantity, or null when no recipe gave one ("salt")
 * @param otherAmounts amounts that could not be converted, e.g. "1 tablespoon" next to 75 g
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
        List<String> otherAmounts,
        List<String> recipes) {

    public RequiredItem {
        otherAmounts = otherAmounts == null ? List.of() : List.copyOf(otherAmounts);
    }

    /** "75 gram + 1 tablespoon", "2", or "" when no recipe gave a quantity. */
    public String amountText() {
        List<String> parts = new ArrayList<>();
        if (quantity != null) {
            parts.add(Amounts.format(quantity, unitName));
        }
        parts.addAll(otherAmounts);
        return String.join(" + ", parts);
    }
}
