package grocery.contracts;

import java.util.List;

/**
 * One slot of a proposed plan. {@code recipeId} is null when the slot could not be filled.
 *
 * @param fixed true when the entry already existed in Mealie and was kept as is
 */
public record PlannedSlot(Slot slot, String recipeId, boolean fixed, double score, List<ScoreTerm> explanation) {
}
