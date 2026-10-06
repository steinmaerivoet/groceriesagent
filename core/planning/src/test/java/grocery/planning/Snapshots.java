package grocery.planning;

import grocery.contracts.MealPlanEntry;
import grocery.contracts.MealType;
import grocery.contracts.PlanningSnapshot;
import grocery.contracts.PlanningWeek;
import grocery.contracts.RecipeSummary;
import grocery.contracts.Slot;
import grocery.contracts.SlotPool;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Builds small hand-written snapshots for planner tests. */
final class Snapshots {

    static final PlanningWeek WEEK = PlanningWeek.parse("2026-W42");

    private final List<RecipeSummary> recipes = new ArrayList<>();
    private final Map<Slot, List<String>> pools = new TreeMap<>();
    private final List<MealPlanEntry> fixed = new ArrayList<>();
    private final List<MealPlanEntry> history = new ArrayList<>();

    static Slot day(int index) {
        return new Slot(WEEK.start().plusDays(index), MealType.DINNER);
    }

    Snapshots recipe(String id, int rating, String... tags) {
        recipes.add(new RecipeSummary(id, id, id, List.of("Dinner"), List.of(tags), rating, 4.0, List.of()));
        return this;
    }

    /** Every day of the week gets a pool with all recipes known so far. */
    Snapshots everyDayAllRecipes() {
        for (int d = 0; d < 7; d++) {
            pool(d, recipes.stream().map(RecipeSummary::id).toArray(String[]::new));
        }
        return this;
    }

    Snapshots pool(int day, String... recipeIds) {
        pools.put(day(day), List.of(recipeIds));
        return this;
    }

    Snapshots fixed(int day, String recipeId) {
        fixed.add(new MealPlanEntry((long) fixed.size() + 1, day(day).date(), MealType.DINNER, recipeId, recipeId));
        return this;
    }

    Snapshots planned(LocalDate date, String recipeId) {
        history.add(new MealPlanEntry(100L + history.size(), date, MealType.DINNER, recipeId, recipeId));
        return this;
    }

    PlanningSnapshot build() {
        return build(WEEK.id());
    }

    PlanningSnapshot build(String runId) {
        List<SlotPool> slotPools = pools.entrySet().stream()
                .map(e -> new SlotPool(e.getKey(), List.of(), "", e.getValue()))
                .toList();
        return new PlanningSnapshot(runId, WEEK.start(), WEEK.end(), Instant.EPOCH, slotPools, fixed, history, recipes);
    }
}
