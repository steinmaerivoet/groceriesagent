package grocery.shoppinglist;

import grocery.contracts.NoteItem;
import grocery.contracts.Problem;
import grocery.contracts.ProblemType;
import grocery.contracts.PurchasePolicy;
import grocery.contracts.RequiredItem;
import grocery.contracts.ShoppingRequirements;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spec §4.3.1: recipe ingredients → scale → aggregate per Food → purchase policy → AUTO items,
 * CHECK questions and STOCKED items assumed in stock. Pure: no Mealie calls.
 */
public final class RequirementsBuilder {

    private final ShoppingSettings settings;
    private final PolicyResolver policies;

    public RequirementsBuilder(ShoppingSettings settings) {
        this.settings = settings;
        this.policies = new PolicyResolver(settings);
    }

    /** One food in one unit dimension, accumulated in the unit it was first seen with. */
    private static final class Line {
        final Ingredient first;
        final Units.Measure measure;
        final PurchasePolicy policy;
        final Set<String> recipes = new LinkedHashSet<>();
        double amount;
        boolean hasQuantity;

        Line(Ingredient first, PurchasePolicy policy) {
            this.first = first;
            this.measure = Units.measure(first.unitName());
            this.policy = policy;
        }
    }

    public ShoppingRequirements build(String runId, List<Recipe> recipes) {
        Map<String, Line> lines = new LinkedHashMap<>();
        List<NoteItem> notes = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();

        for (Recipe recipe : recipes) {
            double scale = 1;
            if (recipe.servings() == null) {
                problems.add(new Problem(ProblemType.RECIPE_CLASSIFICATION_REQUIRED, recipe.name(),
                        recipe.name() + " has no servings; its quantities are used unscaled"));
            } else {
                scale = settings.householdServings() / recipe.servings();
            }
            for (Ingredient ingredient : recipe.ingredients()) {
                if (!ingredient.isParsed()) {
                    notes.add(new NoteItem(ingredient.originalText(), recipe.name()));
                    problems.add(new Problem(ProblemType.UNPARSED_INGREDIENT, recipe.name(),
                            "'" + ingredient.originalText() + "' in " + recipe.name() + " is not linked to a food; added as a note"));
                    continue;
                }
                Units.Measure measure = Units.measure(ingredient.unitName());
                Line line = lines.computeIfAbsent(ingredient.foodId() + "|" + measure.dimension(),
                        k -> new Line(ingredient, policies.resolve(ingredient)));
                line.recipes.add(recipe.name());
                if (ingredient.quantity() != null && ingredient.quantity() > 0) {
                    line.amount += ingredient.quantity() * scale * measure.factor() / line.measure.factor();
                    line.hasQuantity = true;
                }
            }
        }

        List<RequiredItem> auto = new ArrayList<>();
        List<RequiredItem> check = new ArrayList<>();
        List<RequiredItem> stocked = new ArrayList<>();
        lines.values().stream()
                .map(RequirementsBuilder::toItem)
                .sorted(Comparator.comparing((RequiredItem i) -> nullToEmpty(i.labelName())).thenComparing(RequiredItem::foodName))
                .forEach(item -> (switch (item.policy()) {
                    case AUTO -> auto;
                    case STOCKED -> stocked;
                    case CHECK, PREDICT -> check;
                }).add(item));
        return new ShoppingRequirements(runId, auto, check, stocked, notes, problems);
    }

    private static RequiredItem toItem(Line line) {
        Ingredient f = line.first;
        Double quantity = line.hasQuantity ? round(line.amount, f.unitName()) : null;
        return new RequiredItem(f.foodId(), f.foodName(), f.labelId(), f.labelName(), line.policy, quantity,
                f.unitId(), f.unitName(), List.copyOf(line.recipes));
    }

    /** Whole pieces for countable units (1.5 onions → 2); two decimals for mass and volume. */
    private static double round(double amount, String unitName) {
        return Units.isCountable(unitName) ? Math.ceil(amount - 1e-9) : Math.round(amount * 100) / 100.0;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
