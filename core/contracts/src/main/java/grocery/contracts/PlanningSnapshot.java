package grocery.contracts;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the planner needs for one planning run, read from Mealie (output of the mealie module,
 * input of the planning module).
 *
 * @param runId        the planning-run id, e.g. {@code 2026-W42}; also the planner's random seed
 * @param fixedEntries existing meal-plan entries inside the planning week; never replaced
 * @param history      meal-plan entries inside the recency lookback, before the week
 * @param recipes      every recipe referenced by a pool, a fixed entry or the history
 */
public record PlanningSnapshot(
        String runId,
        LocalDate weekStart,
        LocalDate weekEnd,
        Instant capturedAt,
        List<SlotPool> pools,
        List<MealPlanEntry> fixedEntries,
        List<MealPlanEntry> history,
        List<RecipeSummary> recipes) {
}
