package grocery.shoppinglist;

/**
 * An item currently on the Mealie shopping list.
 *
 * @param managed true when its {@code groceries} extra says Grocery Core created it
 */
public record ListItem(String id, String foodId, String unitId, Double quantity, String note, boolean checked,
                       boolean managed) {
}
