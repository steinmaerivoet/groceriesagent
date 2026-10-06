package grocery.shoppinglist;

/**
 * An item the current requirements want on the list.
 *
 * @param key    identity used to match it with what was written before: food + unit, or the note text
 * @param origin {@code meal-plan} or {@code check-answer} (spec §4.3.3)
 */
public record DesiredItem(String key, String foodId, String foodName, String labelId, String unitId, Double quantity,
                          String note, String origin) {

    public String label() {
        return foodName != null ? foodName : note;
    }
}
