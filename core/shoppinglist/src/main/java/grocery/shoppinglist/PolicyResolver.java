package grocery.shoppinglist;

import grocery.contracts.PurchasePolicy;

/**
 * Spec §4.2.2: the Food's own extra wins, then the label default, then AUTO. Before Phase 2,
 * PREDICT is treated as CHECK.
 */
public final class PolicyResolver {

    private final ShoppingSettings settings;

    public PolicyResolver(ShoppingSettings settings) {
        this.settings = settings;
    }

    public PurchasePolicy resolve(Ingredient ingredient) {
        PurchasePolicy policy = ingredient.policyOverride() != null ? ingredient.policyOverride()
                : settings.labelPolicies().getOrDefault(ingredient.labelName(), PurchasePolicy.AUTO);
        return policy == PurchasePolicy.PREDICT && !settings.predictionEnabled() ? PurchasePolicy.CHECK : policy;
    }
}
