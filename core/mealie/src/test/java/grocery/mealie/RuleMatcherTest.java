package grocery.mealie;

import grocery.contracts.MealType;
import grocery.contracts.Slot;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleMatcherTest {

    private static final Slot MONDAY_DINNER = new Slot(LocalDate.of(2026, 10, 12), MealType.DINNER);

    private final PlannerRule anyDinner = new PlannerRule("1", "unset", "dinner", "recipe_category.name IN [\"Dinner\"]");
    private final PlannerRule mondayPasta = new PlannerRule("2", "monday", "dinner", "tags.name IN [\"Pasta\"]");
    private final PlannerRule mondayAnyMeal = new PlannerRule("3", "monday", null, "rating >= 3");
    private final PlannerRule tuesdayDinner = new PlannerRule("4", "tuesday", "dinner", "tags.name IN [\"Fish\"]");
    private final PlannerRule mondayLunch = new PlannerRule("5", "monday", "lunch", "tags.name IN [\"Light meal\"]");
    private final PlannerRule noFilter = new PlannerRule("6", "unset", "unset", "");

    @Test
    void matchesRulesForTheSlotOrUnset() {
        var rules = List.of(anyDinner, mondayPasta, mondayAnyMeal, tuesdayDinner, mondayLunch, noFilter);

        assertThat(RuleMatcher.matching(rules, MONDAY_DINNER))
                .containsExactly(anyDinner, mondayPasta, mondayAnyMeal, noFilter);
    }

    @Test
    void combinesFiltersWithAndLikeMealie() {
        String filter = RuleMatcher.combinedFilter(List.of(anyDinner, noFilter, mondayPasta));

        assertThat(filter).isEqualTo("(recipe_category.name IN [\"Dinner\"]) AND (tags.name IN [\"Pasta\"])");
    }

    @Test
    void noRulesMeansNoFilter() {
        assertThat(RuleMatcher.combinedFilter(List.of())).isEmpty();
    }
}
