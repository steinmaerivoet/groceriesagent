package grocery.contracts;

import java.util.List;

/**
 * What a confirmed meal plan requires (output of the shoppinglist module).
 *
 * @param autoItems      go on the list without asking
 * @param checkQuestions asked in one stock-check checklist before they go on the list
 */
public record ShoppingRequirements(
        String runId,
        List<RequiredItem> autoItems,
        List<RequiredItem> checkQuestions,
        List<NoteItem> noteItems,
        List<Problem> problems) {
}
