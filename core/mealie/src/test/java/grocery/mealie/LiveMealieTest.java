package grocery.mealie;

import grocery.contracts.MealType;
import grocery.contracts.PlanningWeek;
import grocery.contracts.SlotPool;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs against the local Mealie from {@code make setup}; skipped when it is not running.
 * Acceptance criterion (spec §9.1): for every slot, the candidate pool equals the set Mealie's
 * random endpoint would draw from.
 */
class LiveMealieTest {

    private static MealieClient mealie;

    @BeforeAll
    static void connect() {
        try {
            mealie = new MealieClient(MealieConfig.fromEnvironment());
        } catch (IllegalStateException e) {
            mealie = null;
        }
        assumeTrue(mealie != null && mealie.isReachable(), "local Mealie is not running");
    }

    @Test
    void everyPoolMatchesMealiesRandomButton() {
        PoolBuilder builder = new PoolBuilder(mealie);
        for (var slot : PlanningWeek.after(LocalDate.now()).slots(List.of(MealType.DINNER))) {
            SlotPool pool = builder.pool(slot);
            PoolCheck.Result check = PoolCheck.check(mealie, pool, 10);
            assertThat(check.outsidePool()).as("draws outside the pool for %s", slot).isEmpty();
            assertThat(check.emptyAgreed()).as("empty-pool agreement for %s", slot).isTrue();
        }
    }

    @Test
    void snapshotCoversPoolsAndHistory() {
        var snapshot = new SnapshotBuilder(mealie, Clock.systemUTC())
                .build(PlanningWeek.after(LocalDate.now()), List.of(MealType.DINNER), 4);

        assertThat(snapshot.pools()).hasSize(7);
        var recipeIds = snapshot.recipes().stream().map(r -> r.id()).toList();
        snapshot.pools().forEach(p -> assertThat(recipeIds).containsAll(p.recipeIds()));
        snapshot.history().stream().filter(e -> e.recipeId() != null)
                .forEach(e -> assertThat(recipeIds).contains(e.recipeId()));
    }
}
