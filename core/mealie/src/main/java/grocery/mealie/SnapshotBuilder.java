package grocery.mealie;

import com.fasterxml.jackson.databind.JsonNode;
import grocery.contracts.MealPlanEntry;
import grocery.contracts.MealType;
import grocery.contracts.PlanningSnapshot;
import grocery.contracts.PlanningWeek;
import grocery.contracts.RecipeSummary;
import grocery.contracts.Slot;
import grocery.contracts.SlotPool;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** Reads everything one planning run needs from Mealie into a {@link PlanningSnapshot}. */
public class SnapshotBuilder {

    private final MealieClient mealie;
    private final Clock clock;

    public SnapshotBuilder(MealieClient mealie, Clock clock) {
        this.mealie = mealie;
        this.clock = clock;
    }

    /**
     * @param mealTypes     the slots to plan per day (spec §7.1 default: dinner)
     * @param lookbackWeeks how far back meal-plan history counts for recency (default 4)
     */
    public PlanningSnapshot build(PlanningWeek week, List<MealType> mealTypes, int lookbackWeeks) {
        PoolBuilder pools = new PoolBuilder(mealie);
        List<SlotPool> slotPools = week.slots(mealTypes).stream().map(pools::pool).toList();

        Set<Slot> planned = Set.copyOf(week.slots(mealTypes));
        List<MealPlanEntry> fixed = mealie.mealPlans(week.start(), week.end()).stream()
                .filter(e -> planned.contains(e.slot()))
                .toList();
        List<MealPlanEntry> history = mealie.mealPlans(week.start().minusWeeks(lookbackWeeks), week.start().minusDays(1));

        Set<String> referenced = new LinkedHashSet<>();
        slotPools.forEach(p -> referenced.addAll(p.recipeIds()));
        Stream.concat(fixed.stream(), history.stream()).map(MealPlanEntry::recipeId).filter(Objects::nonNull)
                .forEach(referenced::add);

        List<RecipeSummary> recipes = referenced.stream().sorted().map(id -> summary(mealie.recipe(id))).toList();
        return new PlanningSnapshot(week.id(), week.start(), week.end(), clock.instant(), slotPools, fixed, history, recipes);
    }

    static RecipeSummary summary(JsonNode recipe) {
        List<String> foodIds = stream(recipe.path("recipeIngredient"))
                .map(i -> i.path("food").path("id").asText(null))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        JsonNode rating = recipe.path("rating");
        JsonNode servings = recipe.path("recipeServings");
        return new RecipeSummary(
                recipe.path("id").asText(),
                recipe.path("slug").asText(),
                recipe.path("name").asText(),
                names(recipe.path("recipeCategory")),
                names(recipe.path("tags")),
                rating.isNumber() && rating.asDouble() > 0 ? (int) Math.round(rating.asDouble()) : null,
                servings.isNumber() && servings.asDouble() > 0 ? servings.asDouble() : null,
                foodIds);
    }

    private static List<String> names(JsonNode array) {
        return stream(array).map(n -> n.path("name").asText()).toList();
    }

    private static Stream<JsonNode> stream(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false);
    }
}
