package grocery.shoppinglist;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import grocery.contracts.Json;
import grocery.mealie.MealieClient;
import grocery.shoppinglist.Reconciliation.Action;
import grocery.shoppinglist.Reconciliation.Create;
import grocery.shoppinglist.Reconciliation.Delete;
import grocery.shoppinglist.Reconciliation.Update;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the configured Mealie shopping list and applies a {@link Reconciliation} to it. */
public final class MealieShoppingList {

    private final MealieClient mealie;
    private final String listId;
    private final Map<String, JsonNode> raw = new HashMap<>();

    public MealieShoppingList(MealieClient mealie, String listName) {
        this.mealie = mealie;
        this.listId = mealie.shoppingLists().stream()
                .filter(l -> l.path("name").asText().equals(listName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No Mealie shopping list named '" + listName + "'"))
                .path("id").asText();
    }

    public List<ListItem> items() {
        raw.clear();
        List<ListItem> items = new ArrayList<>();
        for (JsonNode node : mealie.shoppingItems(listId)) {
            raw.put(node.path("id").asText(), node);
            JsonNode quantity = node.path("quantity");
            items.add(new ListItem(
                    node.path("id").asText(),
                    node.path("foodId").asText(null),
                    node.path("unitId").asText(null),
                    quantity.isNumber() ? quantity.asDouble() : null,
                    node.path("note").asText(""),
                    node.path("checked").asBoolean(false),
                    MealieRecipes.GroceriesExtra.parse(node.path("extras")).path("managed").asBoolean(false)));
        }
        return items;
    }

    /**
     * Applies the actions and returns what is now managed. Entries for items the household removed
     * are kept for the same planning run, so the removal keeps winning on the next re-run.
     *
     * @return the updated record of written items, plus messages for anything unexpected
     */
    public Result apply(Reconciliation reconciliation, Map<String, WrittenItem> written, String planningRunId) {
        Map<String, WrittenItem> next = new LinkedHashMap<>();
        written.forEach((id, item) -> {
            if (raw.containsKey(id) || item.planningRunId().equals(planningRunId)) {
                next.put(id, item);
            }
        });
        List<String> messages = new ArrayList<>();
        for (Action action : reconciliation.actions()) {
            switch (action) {
                case Create c -> {
                    JsonNode response = mealie.createShoppingItem(body(c.item(), planningRunId));
                    JsonNode created = response.path("createdItems").path(0);
                    if (created.isMissingNode()) {
                        messages.add("Mealie merged " + c.item().label() + " into an existing item; not tracking it");
                    } else {
                        next.put(created.path("id").asText(), written(created, c.item(), planningRunId));
                    }
                }
                case Update u -> {
                    ObjectNode body = ((ObjectNode) raw.get(u.itemId()).deepCopy());
                    body.put("quantity", u.item().quantity() == null ? 0 : u.item().quantity());
                    body.put("note", u.item().note() == null ? "" : u.item().note());
                    JsonNode updated = mealie.updateShoppingItem(u.itemId(), body);
                    JsonNode item = updated.path("updatedItems").path(0);
                    next.put(u.itemId(), written(item.isMissingNode() ? body : item, u.item(), planningRunId));
                }
                case Delete d -> {
                    mealie.deleteShoppingItem(d.itemId());
                    next.remove(d.itemId());
                }
            }
        }
        return new Result(next, messages);
    }

    public record Result(Map<String, WrittenItem> written, List<String> messages) {
    }

    private ObjectNode body(DesiredItem item, String planningRunId) {
        ObjectNode body = Json.MAPPER.createObjectNode()
                .put("shoppingListId", listId)
                .put("quantity", item.quantity() == null ? 0 : item.quantity())
                .put("note", item.note() == null ? "" : item.note());
        if (item.foodId() != null) {
            body.put("foodId", item.foodId());
        }
        if (item.unitId() != null) {
            body.put("unitId", item.unitId());
        }
        if (item.labelId() != null) {
            body.put("labelId", item.labelId());
        }
        ObjectNode groceries = Json.MAPPER.createObjectNode()
                .put("managed", true)
                .put("planningRunId", planningRunId)
                .put("origin", item.origin());
        body.putObject("extras").put(MealieRecipes.GroceriesExtra.KEY, groceries.toString());
        return body;
    }

    private static WrittenItem written(JsonNode node, DesiredItem item, String planningRunId) {
        JsonNode quantity = node.path("quantity");
        return new WrittenItem(node.path("id").asText(), item.key(), item.label(), item.foodId(),
                quantity.isNumber() ? quantity.asDouble() : null, node.path("note").asText(""), planningRunId);
    }
}
