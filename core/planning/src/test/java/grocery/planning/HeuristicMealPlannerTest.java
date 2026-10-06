package grocery.planning;

import grocery.contracts.Json;
import grocery.contracts.PlannedSlot;
import grocery.contracts.PlanningSnapshot;
import grocery.contracts.ProblemType;
import grocery.contracts.ProposedPlan;
import grocery.contracts.RecipeSummary;
import grocery.contracts.SlotPool;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeuristicMealPlannerTest {

    private final MealPlanner planner = new HeuristicMealPlanner(PlannerSettings.defaults());

    /** A pool where the best-rated recipes would break every weekly rule if picked greedily. */
    private Snapshots typicalWeek() {
        return new Snapshots()
                .recipe("bolognese", 5, "Pasta", "Red meat")
                .recipe("lasagne", 5, "Pasta", "Red meat")
                .recipe("carbonara", 5, "Pasta", "Red meat")
                .recipe("steak", 5, "Potatoes", "Red meat")
                .recipe("stew", 5, "Potatoes", "Red meat")
                .recipe("salmon", 3, "Potatoes", "Fish")
                .recipe("risotto", 3, "Rice", "Vegetarian")
                .recipe("dal", 3, "Rice", "Vegetarian")
                .recipe("stirfry", 3, "Other grain", "Vegetarian")
                .recipe("curry", 4, "Rice", "Poultry")
                .everyDayAllRecipes();
    }

    @Test
    void meetsWeeklyTargetsWithoutRepeatsWhenFeasible() {
        ProposedPlan plan = planner.plan(typicalWeek().build());

        assertWeekRules(typicalWeek().build(), plan);
        assertThat(plan.weekTerms()).isEmpty();
    }

    @Test
    void sameInputsAndRunIdGiveTheSamePlan() {
        assertThat(planner.plan(typicalWeek().build("2026-W42"))).isEqualTo(planner.plan(typicalWeek().build("2026-W42")));
    }

    @Test
    void neverPlansOutsideASlotsPool() {
        PlanningSnapshot snapshot = typicalWeek().pool(0, "carbonara", "lasagne").build();

        ProposedPlan plan = planner.plan(snapshot);

        assertThat(plan.slots().getFirst().recipeId()).isIn("carbonara", "lasagne");
        assertAllInPool(snapshot, plan);
    }

    @Test
    void keepsFixedEntriesAndCountsThemTowardsTargets() {
        PlanningSnapshot snapshot = typicalWeek().fixed(2, "salmon").build();

        ProposedPlan plan = planner.plan(snapshot);

        PlannedSlot wednesday = plan.slots().get(2);
        assertThat(wednesday.recipeId()).isEqualTo("salmon");
        assertThat(wednesday.fixed()).isTrue();
        assertThat(plan.slots().stream().filter(s -> "salmon".equals(s.recipeId()))).hasSize(1);
        assertThat(plan.weekTerms()).isEmpty();
    }

    @Test
    void emptyPoolLeavesTheSlotEmptyAndIsReported() {
        PlanningSnapshot snapshot = typicalWeek().pool(1).build();

        ProposedPlan plan = planner.plan(snapshot);

        assertThat(plan.slots().get(1).recipeId()).isNull();
        assertThat(plan.slots().stream().filter(s -> s.recipeId() != null)).hasSize(6);
        assertThat(plan.problems()).anyMatch(p -> p.type() == ProblemType.EMPTY_CANDIDATE_POOL
                && p.subject().equals(Snapshots.day(1).toString()));
    }

    @Test
    void recentlyPlannedRecipesArePenalized() {
        Snapshots snapshots = new Snapshots()
                .recipe("a", 4, "Pasta", "Fish")
                .recipe("b", 4, "Pasta", "Fish")
                .pool(0, "a", "b");
        String withoutHistory = planner.plan(snapshots.build()).slots().getFirst().recipeId();
        String other = withoutHistory.equals("a") ? "b" : "a";

        snapshots.planned(Snapshots.WEEK.start().minusDays(3), withoutHistory);
        ProposedPlan plan = planner.plan(snapshots.build());

        assertThat(plan.slots().getFirst().recipeId()).isEqualTo(other);
    }

    @Test
    void replacingAMealKeepsTheRestAndPicksSomethingElse() {
        PlanningSnapshot snapshot = typicalWeek().build();
        ProposedPlan plan = planner.plan(snapshot);
        PlannedSlot tuesday = plan.slots().get(1);

        ProposedPlan replaced = planner.replace(snapshot, plan, tuesday.slot());

        assertThat(replaced.slots().get(1).recipeId()).isNotNull().isNotEqualTo(tuesday.recipeId());
        for (int i = 0; i < 7; i++) {
            if (i != 1) {
                assertThat(replaced.slots().get(i).recipeId()).isEqualTo(plan.slots().get(i).recipeId());
            }
        }
    }

    @Test
    void fixedEntriesCannotBeReplaced() {
        PlanningSnapshot snapshot = typicalWeek().fixed(2, "salmon").build();
        ProposedPlan plan = planner.plan(snapshot);

        assertThatThrownBy(() -> planner.replace(snapshot, plan, Snapshots.day(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportsRecipesWithMissingOrContradictingPlanningTags() {
        PlanningSnapshot snapshot = typicalWeek()
                .recipe("tacos", 3, "Bread", "Vegetarian", "Legumes")
                .recipe("burger", 3, "Potatoes", "Bread", "Red meat")
                .recipe("fishy curry", 3, "Rice", "Vegetarian", "Fish")
                .recipe("pie", 4, "Comfort food")
                .everyDayAllRecipes()
                .build();

        var problems = planner.plan(snapshot).problems();

        assertThat(problems).filteredOn(p -> p.type() == ProblemType.RECIPE_CLASSIFICATION_REQUIRED)
                .extracting(p -> p.subject())
                .containsExactlyInAnyOrder("fishy curry", "pie");
    }

    @Test
    void plansTheSeededWeekWithinTheRules() {
        PlanningSnapshot snapshot = Json.read(Path.of("../../fixtures/snapshot-2026-W42.json"), PlanningSnapshot.class);

        ProposedPlan plan = planner.plan(snapshot);

        assertAllInPool(snapshot, plan);
        assertWeekRules(snapshot, plan);
        assertThat(plan.weekTerms()).isEmpty();
    }

    @Test
    void reproducesTheCommittedPlanForTheSeededWeek() {
        // Guards determinism across JVM runs: fixtures/plan-2026-W42.json was produced by the demo.
        PlanningSnapshot snapshot = Json.read(Path.of("../../fixtures/snapshot-2026-W42.json"), PlanningSnapshot.class);
        ProposedPlan expected = Json.read(Path.of("../../fixtures/plan-2026-W42.json"), ProposedPlan.class);

        assertThat(planner.plan(snapshot).slots()).extracting(PlannedSlot::recipeId)
                .containsExactlyElementsOf(expected.slots().stream().map(PlannedSlot::recipeId).toList());
    }

    private static void assertAllInPool(PlanningSnapshot snapshot, ProposedPlan plan) {
        Map<Object, SlotPool> pools = snapshot.pools().stream().collect(Collectors.toMap(SlotPool::slot, Function.identity()));
        plan.slots().stream().filter(s -> !s.fixed() && s.recipeId() != null)
                .forEach(s -> assertThat(pools.get(s.slot()).recipeIds()).as("pool of %s", s.slot()).contains(s.recipeId()));
    }

    private static void assertWeekRules(PlanningSnapshot snapshot, ProposedPlan plan) {
        Map<String, RecipeSummary> recipes = snapshot.recipes().stream()
                .collect(Collectors.toMap(RecipeSummary::id, Function.identity()));
        var planned = plan.slots().stream().map(PlannedSlot::recipeId).filter(Objects::nonNull).map(recipes::get).toList();
        assertThat(planned).hasSize(7).doesNotHaveDuplicates();
        assertThat(planned.stream().filter(r -> r.hasTag("Fish"))).hasSizeGreaterThanOrEqualTo(1);
        assertThat(planned.stream().filter(r -> r.hasTag("Vegetarian"))).hasSizeGreaterThanOrEqualTo(2);
        for (String carb : PlannerSettings.defaults().carbohydrateTags()) {
            assertThat(planned.stream().filter(r -> r.hasTag(carb))).as(carb).hasSizeLessThanOrEqualTo(2);
        }
    }
}
