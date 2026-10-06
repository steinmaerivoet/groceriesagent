package grocery.planning;

import grocery.contracts.PlanningSnapshot;
import grocery.contracts.Problem;
import grocery.contracts.ProblemType;
import grocery.contracts.RecipeSummary;
import grocery.contracts.SlotPool;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Spec §3.3 completeness: a plannable recipe has at least one protein / meal-type tag and no
 * contradicting tags (Vegetarian with Fish, say). A recipe may carry several planning tags and
 * counts towards each of them. Missing or contradicting tags are reported, never guessed.
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
        String message = null;
        if (recipe.tags().stream().noneMatch(settings.proteinTags()::contains)) {
            message = "has no protein / meal-type tag";
        } else {
            for (var rule : new TreeMap<>(settings.contradictingTags()).entrySet()) {
                List<String> clashes = rule.getValue().stream().filter(recipe::hasTag).toList();
                if (recipe.hasTag(rule.getKey()) && !clashes.isEmpty()) {
                    message = "has contradicting tags: " + rule.getKey() + " and " + String.join(", ", clashes);
                    break;
                }
            }
        }
        return message == null ? null
                : new Problem(ProblemType.RECIPE_CLASSIFICATION_REQUIRED, recipe.name(), recipe.name() + " " + message);
    }
}
