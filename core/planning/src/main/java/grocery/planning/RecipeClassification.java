package grocery.planning;

import grocery.contracts.PlanningSnapshot;
import grocery.contracts.Problem;
import grocery.contracts.ProblemType;
import grocery.contracts.RecipeSummary;
import grocery.contracts.SlotPool;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Spec §3.3 completeness: a plannable recipe has at most one carbohydrate tag and exactly one
 * protein / meal-type tag. Missing or conflicting tags are reported, never guessed.
 */
final class RecipeClassification {

    private RecipeClassification() {
    }

    static List<Problem> check(PlanningSnapshot snapshot, PlannerSettings settings) {
        Set<String> plannable = new TreeSet<>();
        snapshot.pools().stream().map(SlotPool::recipeIds).forEach(plannable::addAll);
        return snapshot.recipes().stream()
                .filter(r -> plannable.contains(r.id()))
                .map(r -> issue(r, settings))
                .filter(Objects::nonNull)
                .toList();
    }

    private static Problem issue(RecipeSummary recipe, PlannerSettings settings) {
        List<String> protein = recipe.tags().stream().filter(settings.proteinTags()::contains).toList();
        List<String> carbs = recipe.tags().stream().filter(settings.carbohydrateTags()::contains).toList();
        String message = null;
        if (protein.isEmpty()) {
            message = "has no protein / meal-type tag";
        } else if (protein.size() > 1) {
            message = "has several protein / meal-type tags: " + String.join(", ", protein);
        } else if (carbs.size() > 1) {
            message = "has several carbohydrate tags: " + String.join(", ", carbs);
        }
        return message == null ? null
                : new Problem(ProblemType.RECIPE_CLASSIFICATION_REQUIRED, recipe.name(), recipe.name() + " " + message);
    }
}
