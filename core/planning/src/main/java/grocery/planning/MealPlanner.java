package grocery.planning;

import grocery.contracts.PlanningSnapshot;
import grocery.contracts.ProposedPlan;
import grocery.contracts.Slot;

/**
 * Plans a week from a snapshot (spec §4.1.4). Kept behind an interface so the heuristic can be
 * replaced (e.g. by Timefold, NTH-01) without touching pool construction or Mealie integration.
 */
public interface MealPlanner {

    ProposedPlan plan(PlanningSnapshot snapshot);

    /** Re-plans one slot, keeping every other slot as it is and excluding the current recipe. */
    ProposedPlan replace(PlanningSnapshot snapshot, ProposedPlan current, Slot slot);
}
