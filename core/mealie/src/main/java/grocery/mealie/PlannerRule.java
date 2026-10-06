package grocery.mealie;

/**
 * A Mealie meal-planner rule (spec §3.4.1). {@code day} and {@code entryType} use Mealie's values;
 * {@code "unset"} (or null) means "any".
 */
public record PlannerRule(String id, String day, String entryType, String queryFilterString) {
}
