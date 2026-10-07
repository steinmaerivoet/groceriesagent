package grocery.shoppinglist;

import grocery.contracts.Amounts;
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
 * Spec §4.3.1: recipe ingredients → scale → aggregate per Food (one item each) → purchase policy → AUTO items,
 * CHECK questions and STOCKED items assumed in stock. Pure: no Mealie calls.
 */
public final class RequirementsBuilder {

    private final ShoppingSettings settings;
    private final PolicyResolver policies;

    public RequirementsBuilder(ShoppingSettings settings) {
        this.settings = settings;
        this.policies = new PolicyResolver(settings);
    }

    /** One food, accumulated per unit dimension in the unit each dimension was first seen with. */
    private static final class Line {
        final Ingredient first;
        final PurchasePolicy policy;
        final Set<String> recipes = new LinkedHashSet<>();
        final Map<String, Part> parts = new LinkedHashMap<>();

        Line(Ingredient first, PurchasePolicy policy) {
            this.first = first;
            this.policy = policy;
        }
    }

    private static final class Part {
        final Ingredient first;
        final Units.Measure measure;
        double amount;
        boolean hasQuantity;

        Part(Ingredient first) {
            this.first = first;
            this.measure = Units.measure(first.unitName());
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
                Line line = lines.computeIfAbsent(ingredient.foodId(), k -> new Line(ingredient, policies.resolve(ingredient)));
                line.recipes.add(recipe.name());
                Part part = line.parts.computeIfAbsent(measure.dimension(), k -> new Part(ingredient));
                if (ingredient.quantity() != null && ingredient.quantity() > 0) {
                    part.amount += ingredient.quantity() * scale * measure.factor() / part.measure.factor();
                    part.hasQuantity = true;
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

    /**
     * One item per Food. The quantity is in the first mass unit seen, or else the first unit seen;
     * other units are converted into it through the Food's weight per unit, and amounts that can't
     * be converted are listed next to it ("75 g + 1 tablespoon") rather than guessed.
     */
    private static RequiredItem toItem(Line line) {
        List<Part> parts = line.parts.values().stream().filter(p -> p.hasQuantity).toList();
        Part target = parts.stream().filter(p -> p.measure.dimension().equals("mass")).findFirst()
                .orElse(parts.isEmpty() ? line.parts.values().iterator().next() : parts.getFirst());
        Map<String, Double> gramsPer = line.first.gramsPer();
        Double targetGrams = Units.grams(1, target.first.unitName(), gramsPer);
        double amount = target.amount;
        List<String> other = new ArrayList<>();
        for (Part part : parts) {
            if (part == target) {
                continue;
            }
            Double grams = Units.grams(part.amount, part.first.unitName(), gramsPer);
            if (grams != null && targetGrams != null) {
                amount += grams / targetGrams;
            } else {
                other.add(Amounts.format(round(part.amount, part.first.unitName()), part.first.unitName()));
            }
        }
        Ingredient f = target.first;
        Double quantity = parts.isEmpty() ? null : round(amount, f.unitName());
        return new RequiredItem(f.foodId(), f.foodName(), f.labelId(), f.labelName(), line.policy, quantity,
                f.unitId(), f.unitName(), other, List.copyOf(line.recipes));
    }

    /** Whole pieces for countable units (1.5 onions → 2); two decimals for mass and volume. */
    private static double round(double amount, String unitName) {
        return Units.isCountable(unitName) ? Math.ceil(amount - 1e-9) : Math.round(amount * 100) / 100.0;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
