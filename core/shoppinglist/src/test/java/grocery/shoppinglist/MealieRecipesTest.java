package grocery.shoppinglist;

import grocery.contracts.Json;
import grocery.contracts.PurchasePolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MealieRecipesTest {

    @Test
    void readsPolicyAndWeightsFromTheGroceriesExtra() throws Exception {
        var extras = Json.MAPPER.readTree("""
                {"groceries": "{\\"purchasePolicy\\": \\"CHECK\\", \\"gramsPer\\": {\\"tablespoon\\": 16}}"}""");

        assertThat(MealieRecipes.policyOverride(extras)).isEqualTo(PurchasePolicy.CHECK);
        assertThat(MealieRecipes.gramsPer(extras)).containsExactlyEntriesOf(java.util.Map.of("tablespoon", 16.0));
    }

    @Test
    void aFoodWithoutExtrasHasNoWeights() {
        assertThat(MealieRecipes.gramsPer(Json.MAPPER.createObjectNode())).isEmpty();
    }
}
