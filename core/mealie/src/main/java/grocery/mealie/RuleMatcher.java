package grocery.mealie;

import grocery.contracts.Slot;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Mealie's rule semantics, reproduced so Grocery Core draws from exactly the pool Mealie's random
 * button would (spec §3.4.1). Mirrors {@code RepositoryMealPlanRules.get_rules} and
 * {@code QueryFilterBuilder.combine_filters} in Mealie v3.
 */
public final class RuleMatcher {

    private RuleMatcher() {
    }

    /** Every rule whose day and entry type match the slot, or are unset. */
    public static List<PlannerRule> matching(List<PlannerRule> rules, Slot slot) {
        String day = slot.date().getDayOfWeek().name().toLowerCase(Locale.ROOT);
        String type = slot.mealType().mealieValue();
        return rules.stream()
                .filter(r -> isUnset(r.day()) || r.day().equals(day))
                .filter(r -> isUnset(r.entryType()) || r.entryType().equals(type))
                .toList();
    }

    /** The rules' filters joined with AND, each in parentheses; empty filters are skipped. */
    public static String combinedFilter(List<PlannerRule> rules) {
        return rules.stream()
                .map(PlannerRule::queryFilterString)
                .filter(f -> f != null && !f.isBlank())
                .map(f -> "(" + f + ")")
                .collect(Collectors.joining(" AND "));
    }

    private static boolean isUnset(String value) {
        return value == null || value.isBlank() || value.equals("unset");
    }
}
