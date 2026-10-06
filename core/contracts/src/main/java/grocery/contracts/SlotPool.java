package grocery.contracts;

import java.util.List;

/**
 * The candidate pool of one slot: the recipes Mealie's random button would draw from (spec §3.4.3).
 *
 * @param ruleIds     the planner rules that matched the slot
 * @param queryFilter the combined filter that produced the pool, or empty when no rule has one
 */
public record SlotPool(Slot slot, List<String> ruleIds, String queryFilter, List<String> recipeIds) {
}
