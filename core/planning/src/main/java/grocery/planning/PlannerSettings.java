package grocery.planning;

import java.util.List;
import java.util.Map;

/**
 * Planner configuration (spec §4.1.2, §4.1.3, §7.1). Every weight is configurable; the defaults
 * are a starting point to tune against real weeks.
 *
 * @param weeklyMinimums      at least this many meals per week with the tag, e.g. Fish ≥ 1
 * @param maxPerCarbohydrate  at most this many meals per week with the same carbohydrate tag
 * @param carbohydrateTags    tags that classify a recipe's carbohydrate (any number per recipe)
 * @param proteinTags         tags that classify a recipe's protein / meal type (at least one)
 * @param contradictingTags   tag → tags that cannot appear on the same recipe, e.g. Vegetarian → Fish
 * @param lookbackDays        how far back a planned recipe still counts as recent
 * @param improvementRounds   upper bound on local-improvement passes
 */
public record PlannerSettings(
        Map<String, Integer> weeklyMinimums,
        int maxPerCarbohydrate,
        List<String> carbohydrateTags,
        List<String> proteinTags,
        Map<String, List<String>> contradictingTags,
        int lookbackDays,
        Weights weights,
        int improvementRounds) {

    /**
     * @param rating          per rating point above (or below) 3
     * @param recency         penalty for a recipe planned yesterday, fading to 0 at the lookback edge
     * @param sameProtein     penalty when neighbouring days share a protein tag
     * @param sameCarb        penalty when neighbouring days share a carbohydrate tag
     * @param sharedFood      bonus per ingredient Food shared with another planned meal
     * @param sharedFoodCap   maximum ingredient-reuse bonus per slot
     * @param repeat          MEDIUM penalty per extra use of a recipe within the week
     * @param missedTarget    MEDIUM penalty per meal short of a weekly minimum
     * @param carbOverLimit   MEDIUM penalty per meal over the carbohydrate limit
     * @param jitter          maximum seeded tie-breaker added per slot and recipe
     */
    public record Weights(
            double rating,
            double recency,
            double sameProtein,
            double sameCarb,
            double sharedFood,
            double sharedFoodCap,
            double repeat,
            double missedTarget,
            double carbOverLimit,
            double jitter) {
    }

    public static PlannerSettings defaults() {
        return new PlannerSettings(
                Map.of("Fish", 1, "Vegetarian", 2),
                2,
                List.of("Pasta", "Rice", "Potatoes", "Other grain", "Bread"),
                List.of("Fish", "Poultry", "Red meat", "Vegetarian", "Legumes"),
                Map.of("Vegetarian", List.of("Fish", "Poultry", "Red meat")),
                28,
                new Weights(1.0, 3.0, 1.0, 0.5, 0.1, 0.5, 20.0, 8.0, 8.0, 0.05),
                50);
    }
}
