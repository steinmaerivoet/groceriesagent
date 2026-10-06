package grocery.contracts;

import java.util.List;

/**
 * What a confirmed meal plan requires (output of the shoppinglist module).
 *
 * @param autoItems      go on the list without asking
 * @param checkQuestions asked in one stock-check checklist before they go on the list
 * @param assumedInStock needed by a recipe but assumed in stock (policy STOCKED); shown, not asked
 */
public record ShoppingRequirements(
        String runId,
        List<RequiredItem> autoItems,
        List<RequiredItem> checkQuestions,
        List<RequiredItem> assumedInStock,
        List<NoteItem> noteItems,
        List<Problem> problems) {
}
