package grocery.shoppinglist;

import grocery.contracts.ProblemType;
import grocery.contracts.PurchasePolicy;
import grocery.contracts.RequiredItem;
import grocery.contracts.ShoppingRequirements;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementsBuilderTest {

    private final RequirementsBuilder builder = new RequirementsBuilder(ShoppingSettings.defaults());

    static Ingredient food(String name, String label, Double quantity, String unit) {
        return new Ingredient("food-" + name, name, "label-" + label, label, null, null, quantity, unit == null ? null : "unit-" + unit, unit,
                quantity + " " + unit + " " + name);
    }

    static Ingredient override(Ingredient i, PurchasePolicy policy) {
        return new Ingredient(i.foodId(), i.foodName(), i.labelId(), i.labelName(), policy, i.gramsPer(), i.quantity(), i.unitId(), i.unitName(),
                i.originalText());
    }

    @Test
    void scalesToHouseholdServingsAndSumsConvertibleUnits() {
        var a = new Recipe("a", "Stir-fry", 4.0, List.of(food("broccoli", "Vegetables", 400.0, "gram")));
        var b = new Recipe("b", "Pasta", 2.0, List.of(food("broccoli", "Vegetables", 0.35, "kilogram")));

        ShoppingRequirements r = builder.build("run", List.of(a, b));

        assertThat(r.autoItems()).singleElement().satisfies(i -> {
            assertThat(i.quantity()).isEqualTo(550.0); // 400 g × 2/4 + 350 g × 2/2
            assertThat(i.unitName()).isEqualTo("gram");
            assertThat(i.recipes()).containsExactly("Stir-fry", "Pasta");
        });
    }

    static Ingredient weighing(Ingredient i, Map<String, Double> gramsPer) {
        return new Ingredient(i.foodId(), i.foodName(), i.labelId(), i.labelName(), i.policyOverride(), gramsPer, i.quantity(),
                i.unitId(), i.unitName(), i.originalText());
    }

    @Test
    void convertsOtherUnitsToGramsWithTheFoodsWeightPerUnit() {
        var curry = new Recipe("a", "Curry", 2.0, List.of(
                weighing(food("peanut butter", "Canned & jars", 1.0, "tablespoon"), Map.of("tablespoon", 16.0)),
                weighing(food("peanut butter", "Canned & jars", 75.0, "gram"), Map.of("tablespoon", 16.0))));
        var stew = new Recipe("b", "Stew", 2.0, List.of(
                weighing(food("carrot", "Vegetables", 2.0, null), Map.of("piece", 80.0)),
                weighing(food("carrot", "Vegetables", 100.0, "gram"), Map.of("piece", 80.0))));

        ShoppingRequirements r = builder.build("run", List.of(curry, stew));

        assertThat(r.checkQuestions()).singleElement().satisfies(i -> {
            assertThat(i.foodName()).isEqualTo("peanut butter");
            assertThat(i.amountText()).isEqualTo("91 gram");
        });
        assertThat(r.autoItems()).singleElement().satisfies(i -> assertThat(i.amountText()).isEqualTo("260 gram"));
    }

    @Test
    void aVolumeWeightCoversEveryVolumeUnit() {
        var recipe = new Recipe("a", "Soup", 2.0, List.of(
                weighing(food("crème fraîche", "Vegetables", 200.0, "millilitre"), Map.of("millilitre", 1.0)),
                weighing(food("crème fraîche", "Vegetables", 2.0, "tablespoon"), Map.of("millilitre", 1.0))));

        assertThat(builder.build("run", List.of(recipe)).autoItems()).singleElement()
                .satisfies(i -> assertThat(i.amountText()).isEqualTo("230 millilitre"));
    }

    @Test
    void listsAmountsItCannotConvertOnTheSameLine() {
        var recipe = new Recipe("a", "Curry", 2.0, List.of(
                food("peanut butter", "Canned & jars", 1.0, "tablespoon"),
                food("peanut butter", "Canned & jars", 75.0, "gram")));

        ShoppingRequirements r = builder.build("run", List.of(recipe));

        assertThat(r.checkQuestions()).singleElement().satisfies(i -> {
            assertThat(i.quantity()).isEqualTo(75.0);
            assertThat(i.otherAmounts()).containsExactly("1 tablespoon");
            assertThat(i.amountText()).isEqualTo("75 gram + 1 tablespoon");
        });
        assertThat(DesiredItems.from(r, Set.of("food-peanut butter"))).singleElement()
                .satisfies(d -> assertThat(d.note()).isEqualTo("+ 1 tablespoon"));
    }

    @Test
    void roundsCountableUnitsUpToWholePieces() {
        var recipe = new Recipe("a", "Soup", 4.0, List.of(food("onion", "Vegetables", 3.0, null)));

        assertThat(builder.build("run", List.of(recipe)).autoItems().getFirst().quantity()).isEqualTo(2.0);
    }

    @Test
    void resolvesPolicyFromFoodThenLabelThenAuto() {
        var recipe = new Recipe("a", "Stew", 2.0, List.of(
                food("carrot", "Vegetables", 1.0, null),                                  // label default AUTO
                food("rice", "Pasta & grains", 100.0, "gram"),                            // label default CHECK
                override(food("onion", "Vegetables", 1.0, null), PurchasePolicy.CHECK),   // food override
                food("yoghurt", "Dairy & eggs", 100.0, "gram"),                           // PREDICT → CHECK in Phase 1
                food("mystery", "Unknown label", 1.0, null),                              // no default → AUTO
                food("salt", "Pantry", null, null),                                       // label default STOCKED
                override(food("tahini", "Pantry", 2.0, "tablespoon"), PurchasePolicy.CHECK)));  // non-staple in the pantry

        ShoppingRequirements r = builder.build("run", List.of(recipe));

        assertThat(r.autoItems()).extracting(RequiredItem::foodName).containsExactlyInAnyOrder("carrot", "mystery");
        assertThat(r.checkQuestions()).extracting(RequiredItem::foodName).containsExactlyInAnyOrder("rice", "onion", "yoghurt", "tahini");
        assertThat(r.assumedInStock()).extracting(RequiredItem::foodName).containsExactly("salt");
        assertThat(r.checkQuestions()).allMatch(i -> i.policy() == PurchasePolicy.CHECK);
    }

    @Test
    void unparsedIngredientsBecomeNotesAndAreReported() {
        var unparsed = new Ingredient(null, null, null, null, null, null, null, null, null, "a splash of something nice");
        var recipe = new Recipe("a", "Curry", 2.0, List.of(unparsed));

        ShoppingRequirements r = builder.build("run", List.of(recipe));

        assertThat(r.noteItems()).singleElement().satisfies(n -> assertThat(n.text()).isEqualTo("a splash of something nice"));
        assertThat(r.problems()).singleElement().satisfies(p -> assertThat(p.type()).isEqualTo(ProblemType.UNPARSED_INGREDIENT));
    }

    @Test
    void recipesWithoutServingsAreUsedUnscaledAndReported() {
        var recipe = new Recipe("a", "Mystery Dish", null, List.of(food("leek", "Vegetables", 3.0, null)));

        ShoppingRequirements r = builder.build("run", List.of(recipe));

        assertThat(r.autoItems().getFirst().quantity()).isEqualTo(3.0);
        assertThat(r.problems()).singleElement().satisfies(p -> assertThat(p.type()).isEqualTo(ProblemType.RECIPE_CLASSIFICATION_REQUIRED));
    }
}
