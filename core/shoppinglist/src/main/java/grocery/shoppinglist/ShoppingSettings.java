package grocery.shoppinglist;

import grocery.contracts.PurchasePolicy;

import java.util.Map;
import java.util.Set;

/**
 * Shopping-list configuration (spec §7.1). The label defaults are the single home of the
 * per-label purchase policy (OQ-02): Mealie Food extras hold only per-food overrides.
 *
 * @param householdServings  quantities are scaled to this many servings
 * @param labelPolicies      default purchase policy per Food label
 * @param stockUpLabels      labels whose foods may be bought early on promotion (NTH-05, unused yet)
 * @param predictionEnabled  Phase 2; until then PREDICT behaves as CHECK (spec §4.2.2)
 */
public record ShoppingSettings(
        double householdServings,
        Map<String, PurchasePolicy> labelPolicies,
        Set<String> stockUpLabels,
        boolean predictionEnabled) {

    public static ShoppingSettings defaults() {
        return new ShoppingSettings(2, Map.ofEntries(
                Map.entry("Vegetables", PurchasePolicy.AUTO),
                Map.entry("Fruit", PurchasePolicy.PREDICT),
                Map.entry("Fish", PurchasePolicy.AUTO),
                Map.entry("Meat", PurchasePolicy.AUTO),
                Map.entry("Dairy & eggs", PurchasePolicy.PREDICT),
                Map.entry("Bread", PurchasePolicy.PREDICT),
                Map.entry("Pasta & grains", PurchasePolicy.CHECK),
                Map.entry("Canned & jars", PurchasePolicy.CHECK),
                Map.entry("Pantry", PurchasePolicy.CHECK),
                Map.entry("Frozen", PurchasePolicy.CHECK),
                Map.entry("Drinks", PurchasePolicy.PREDICT),
                Map.entry("Snacks", PurchasePolicy.PREDICT),
                Map.entry("Household", PurchasePolicy.PREDICT),
                Map.entry("Personal care", PurchasePolicy.PREDICT)),
                Set.of("Pasta & grains", "Canned & jars", "Pantry", "Frozen", "Drinks", "Snacks", "Household", "Personal care"),
                false);
    }

    public ShoppingSettings withServings(double servings) {
        return new ShoppingSettings(servings, labelPolicies, stockUpLabels, predictionEnabled);
    }
}
