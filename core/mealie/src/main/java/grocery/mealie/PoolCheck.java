package grocery.mealie;

import grocery.contracts.MealPlanEntry;
import grocery.contracts.SlotPool;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks a pool against Mealie's own random button: every draw must land inside the pool, and an
 * empty pool must make Mealie answer 404. Mealie's random endpoint creates a meal-plan entry for
 * each draw; those entries are deleted again.
 */
public final class PoolCheck {

    /** {@code outsidePool} lists drawn recipe ids that our pool did not contain. */
    public record Result(SlotPool pool, int draws, Set<String> drawn, Set<String> outsidePool, boolean emptyAgreed) {
        public boolean ok() {
            return outsidePool.isEmpty() && emptyAgreed;
        }
    }

    private PoolCheck() {
    }

    public static Result check(MealieClient mealie, SlotPool pool, int draws) {
        Set<String> drawn = new TreeSet<>();
        List<MealPlanEntry> created = new ArrayList<>();
        boolean mealieFoundNothing = false;
        try {
            for (int i = 0; i < draws; i++) {
                Optional<MealPlanEntry> entry = mealie.randomMealPlan(pool.slot().date(), pool.slot().mealType());
                if (entry.isEmpty()) {
                    mealieFoundNothing = true;
                    break;
                }
                created.add(entry.get());
                drawn.add(entry.get().recipeId());
            }
        } finally {
            created.forEach(e -> mealie.deleteMealPlan(e.id()));
        }
        Set<String> outside = new TreeSet<>(drawn);
        outside.removeAll(Set.copyOf(pool.recipeIds()));
        boolean emptyAgreed = mealieFoundNothing == pool.recipeIds().isEmpty();
        return new Result(pool, draws, drawn, outside, emptyAgreed);
    }
}
