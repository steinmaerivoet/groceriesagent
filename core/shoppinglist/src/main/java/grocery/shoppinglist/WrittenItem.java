package grocery.shoppinglist;

/**
 * What Grocery Core last wrote for a managed item (the {@code ManagedListItem} of spec §3.5).
 * Comparing it with the live item tells whether the household changed it since.
 */
public record WrittenItem(String itemId, String key, String label, String foodId, Double quantity, String note,
                          String planningRunId) {
}
