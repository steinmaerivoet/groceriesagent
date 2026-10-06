package grocery.planning;

import grocery.contracts.MealPlanEntry;
import grocery.contracts.PlanningSnapshot;
import grocery.contracts.RecipeSummary;
import grocery.contracts.ScoreTerm;
import grocery.contracts.Slot;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Scores a (partial) week plan (spec §4.1.3, Phase 1 terms):
 * <pre>
 * score = qualityBenefit (rating, variety, ingredient reuse) - recencyPenalty - mediumConstraintPenalty
 * </pre>
 * A plan maps slots to recipe ids; a null recipe id is a filled slot without recipe (a note).
 */
final class PlanScorer {

    /** Score of a plan: per-slot contributions plus week-level constraint penalties. */
    record Evaluation(double total, Map<Slot, List<ScoreTerm>> slotTerms, List<ScoreTerm> weekTerms) {

        double slotScore(Slot slot) {
            return slotTerms.getOrDefault(slot, List.of()).stream().mapToDouble(ScoreTerm::value).sum();
        }
    }

    private final PlannerSettings settings;
    private final PlannerSettings.Weights w;
    private final Map<String, RecipeSummary> recipes;
    private final Map<String, LocalDate> lastPlanned = new HashMap<>();
    private final LocalDate weekStart;
    private final String runId;

    PlanScorer(PlanningSnapshot snapshot, PlannerSettings settings) {
        this.settings = settings;
        this.w = settings.weights();
        this.recipes = snapshot.recipes().stream().collect(Collectors.toMap(RecipeSummary::id, Function.identity()));
        this.weekStart = snapshot.weekStart();
        this.runId = snapshot.runId();
        for (MealPlanEntry e : snapshot.history()) {
            if (e.recipeId() != null) {
                lastPlanned.merge(e.recipeId(), e.date(), (a, b) -> a.isAfter(b) ? a : b);
            }
        }
    }

    RecipeSummary recipe(String id) {
        return recipes.get(id);
    }

    double total(Map<Slot, String> plan) {
        return evaluate(plan).total();
    }

    Evaluation evaluate(Map<Slot, String> plan) {
        Map<Slot, List<ScoreTerm>> slotTerms = new LinkedHashMap<>();
        List<Slot> slots = plan.keySet().stream().sorted().toList();
        for (Slot slot : slots) {
            RecipeSummary recipe = recipes.get(plan.get(slot));
            if (recipe != null) {
                slotTerms.put(slot, slotTerms(slot, recipe, plan));
            }
        }
        List<ScoreTerm> weekTerms = weekTerms(plan);
        double total = slotTerms.values().stream().flatMap(List::stream).mapToDouble(ScoreTerm::value).sum()
                + weekTerms.stream().mapToDouble(ScoreTerm::value).sum()
                + slots.stream().mapToDouble(s -> jitter(s, plan.get(s))).sum();
        return new Evaluation(total, slotTerms, weekTerms);
    }

    private List<ScoreTerm> slotTerms(Slot slot, RecipeSummary recipe, Map<Slot, String> plan) {
        List<ScoreTerm> terms = new ArrayList<>();
        if (recipe.rating() != null) {
            terms.add(new ScoreTerm("rating", w.rating() * (recipe.rating() - 3), "rated " + recipe.rating() + "/5"));
        }
        LocalDate last = lastPlanned.get(recipe.id());
        if (last != null) {
            long daysAgo = ChronoUnit.DAYS.between(last, weekStart);
            double fade = Math.max(0, 1 - (daysAgo - 1) / (double) settings.lookbackDays());
            if (fade > 0) {
                terms.add(new ScoreTerm("recency", -w.recency() * fade, "last planned " + last));
            }
        }
        // Variety: compare with the same meal on the previous day.
        RecipeSummary previous = recipes.get(plan.get(new Slot(slot.date().minusDays(1), slot.mealType())));
        if (previous != null) {
            for (String tag : shared(recipe, previous, settings.proteinTags())) {
                terms.add(new ScoreTerm("variety", -w.sameProtein(), tag + " the day before too"));
            }
            for (String tag : shared(recipe, previous, settings.carbohydrateTags())) {
                terms.add(new ScoreTerm("variety", -w.sameCarb(), tag + " the day before too"));
            }
        }
        long sharedFoods = plan.entrySet().stream()
                .filter(e -> !e.getKey().equals(slot) && recipes.containsKey(e.getValue()))
                .flatMap(e -> recipes.get(e.getValue()).foodIds().stream())
                .distinct()
                .filter(recipe.foodIds()::contains)
                .count();
        if (sharedFoods > 0) {
            terms.add(new ScoreTerm("ingredient reuse", Math.min(w.sharedFoodCap(), w.sharedFood() * sharedFoods),
                    sharedFoods + " ingredients shared with other meals"));
        }
        settings.weeklyMinimums().forEach((tag, min) -> {
            if (recipe.hasTag(tag)) {
                terms.add(new ScoreTerm("weekly target", 0, "counts towards " + tag + " ≥ " + min));
            }
        });
        return terms;
    }

    private List<ScoreTerm> weekTerms(Map<Slot, String> plan) {
        List<RecipeSummary> planned = plan.values().stream().filter(Objects::nonNull).map(recipes::get)
                .filter(Objects::nonNull).toList();
        List<ScoreTerm> terms = new ArrayList<>();
        Map<String, Long> uses = planned.stream().collect(Collectors.groupingBy(RecipeSummary::id, Collectors.counting()));
        uses.forEach((id, n) -> {
            if (n > 1) {
                terms.add(new ScoreTerm("repeat", -w.repeat() * (n - 1), recipes.get(id).name() + " planned " + n + " times"));
            }
        });
        settings.weeklyMinimums().forEach((tag, min) -> {
            long count = planned.stream().filter(r -> r.hasTag(tag)).count();
            if (count < min) {
                terms.add(new ScoreTerm("weekly target", -w.missedTarget() * (min - count),
                        "only " + count + " " + tag + " meals, target ≥ " + min));
            }
        });
        for (String carb : settings.carbohydrateTags()) {
            long count = planned.stream().filter(r -> r.hasTag(carb)).count();
            if (count > settings.maxPerCarbohydrate()) {
                terms.add(new ScoreTerm("carbohydrate limit", -w.carbOverLimit() * (count - settings.maxPerCarbohydrate()),
                        count + " " + carb + " meals, limit " + settings.maxPerCarbohydrate()));
            }
        }
        return terms;
    }

    /** Seeded tie-breaker: the same run id, slot and recipe always get the same value. */
    private double jitter(Slot slot, String recipeId) {
        if (recipeId == null || w.jitter() == 0) {
            return 0;
        }
        // Only value-based hash codes (String, LocalDate): enum and object hashes differ between JVM runs.
        long seed = ((long) runId.hashCode() << 32) ^ Objects.hash(slot.date(), slot.mealType().name(), recipeId);
        return new SplittableRandom(seed).nextDouble() * w.jitter();
    }

    private static Set<String> shared(RecipeSummary a, RecipeSummary b, List<String> tags) {
        Set<String> result = new HashSet<>(tags);
        result.retainAll(a.tags());
        result.retainAll(b.tags());
        return result;
    }
}
