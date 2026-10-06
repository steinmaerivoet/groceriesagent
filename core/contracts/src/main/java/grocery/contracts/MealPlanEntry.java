package grocery.contracts;

import java.time.LocalDate;

/**
 * A Mealie meal-plan entry. {@code recipeId} is null for note-only entries.
 */
public record MealPlanEntry(Long id, LocalDate date, MealType mealType, String recipeId, String title) {

    public Slot slot() {
        return new Slot(date, mealType);
    }
}
