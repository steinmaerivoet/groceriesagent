package grocery.mealie;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import grocery.contracts.Json;
import grocery.contracts.MealPlanEntry;
import grocery.contracts.MealType;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Thin client for the parts of the Mealie REST API Grocery Core uses. Returns raw JSON where the
 * shape is still being explored (POC 1) and contract records where it is settled.
 */
public class MealieClient {

    private static final int PAGE_SIZE = 100;

    private final MealieConfig config;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // uvicorn rejects the h2c upgrade Java sends on plain HTTP
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public MealieClient(MealieConfig config) {
        this.config = config;
    }

    public URI baseUrl() {
        return config.baseUrl();
    }

    // ---------------------------------------------------------------- planner rules and recipes

    public List<PlannerRule> plannerRules() {
        return getAll("/api/households/mealplans/rules", null).stream()
                .map(r -> new PlannerRule(r.path("id").asText(), r.path("day").asText(null),
                        r.path("entryType").asText(null), r.path("queryFilterString").asText("")))
                .toList();
    }

    /** All recipe summaries matching {@code queryFilter} (all pages), as the UI user sees them. */
    public List<JsonNode> recipes(String queryFilter) {
        return getAll("/api/recipes", queryFilter);
    }

    /**
     * One page of recipes for a free-text search (name, description, ingredients) and/or tags,
     * as Mealie's search box does. Tags are ids; a recipe must carry all of them.
     */
    public List<JsonNode> searchRecipes(String search, List<String> tagIds, int limit) {
        StringBuilder path = new StringBuilder("/api/recipes?page=1&perPage=").append(limit)
                .append("&orderBy=rating&orderDirection=desc&requireAllTags=true");
        if (search != null && !search.isBlank()) {
            path.append("&search=").append(URLEncoder.encode(search, StandardCharsets.UTF_8));
        }
        tagIds.forEach(id -> path.append("&tags=").append(URLEncoder.encode(id, StandardCharsets.UTF_8)));
        List<JsonNode> items = new ArrayList<>();
        get(path.toString()).path("items").forEach(items::add);
        return items;
    }

    public List<JsonNode> tags() {
        return getAll("/api/organizers/tags", null);
    }

    /** The household's group, whose slug is part of recipe URLs in the UI. */
    public JsonNode group() {
        return get("/api/groups/self");
    }

    /** The full recipe, including parsed ingredients. */
    public JsonNode recipe(String slugOrId) {
        return get("/api/recipes/" + slugOrId);
    }

    // ---------------------------------------------------------------- meal plans

    public List<MealPlanEntry> mealPlans(LocalDate start, LocalDate end) {
        return getAll("/api/households/mealplans?start_date=" + start + "&end_date=" + end, null).stream()
                .map(MealieClient::toEntry)
                .toList();
    }

    public MealPlanEntry createMealPlan(LocalDate date, MealType type, String recipeId) {
        ObjectNode body = Json.MAPPER.createObjectNode()
                .put("date", date.toString())
                .put("entryType", type.mealieValue())
                .put("recipeId", recipeId);
        return toEntry(send("POST", "/api/households/mealplans", body));
    }

    public void deleteMealPlan(long id) {
        send("DELETE", "/api/households/mealplans/" + id, null);
    }

    /**
     * Mealie's random button: picks a recipe with the slot's rules and <b>creates a meal-plan
     * entry</b> for it. Empty when no recipe matches (HTTP 404).
     */
    public Optional<MealPlanEntry> randomMealPlan(LocalDate date, MealType type) {
        ObjectNode body = Json.MAPPER.createObjectNode()
                .put("date", date.toString())
                .put("entryType", type.mealieValue());
        try {
            return Optional.of(toEntry(send("POST", "/api/households/mealplans/random", body)));
        } catch (MealieException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    // ---------------------------------------------------------------- foods and shopping lists

    public List<JsonNode> foods() {
        return getAll("/api/foods", null);
    }

    public List<JsonNode> shoppingLists() {
        return getAll("/api/households/shopping/lists", null);
    }

    public List<JsonNode> shoppingItems(String listId) {
        return getAll("/api/households/shopping/items", "shoppingListId = \"" + listId + "\"");
    }

    /** Creates an item and returns it. Mealie answers with created/updated items (it may merge). */
    public JsonNode createShoppingItem(ObjectNode item) {
        return send("POST", "/api/households/shopping/items", item);
    }

    public JsonNode updateShoppingItem(String id, ObjectNode item) {
        return send("PUT", "/api/households/shopping/items/" + id, item);
    }

    public void deleteShoppingItem(String id) {
        send("DELETE", "/api/households/shopping/items/" + id, null);
    }

    // ---------------------------------------------------------------- HTTP

    public boolean isReachable() {
        try {
            get("/api/app/about");
            return true;
        } catch (MealieException e) {
            return false;
        }
    }

    public JsonNode get(String path) {
        return send("GET", path, null);
    }

    private List<JsonNode> getAll(String path, String queryFilter) {
        List<JsonNode> items = new ArrayList<>();
        String sep = path.contains("?") ? "&" : "?";
        String filter = queryFilter == null || queryFilter.isBlank() ? ""
                : "&queryFilter=" + URLEncoder.encode(queryFilter, StandardCharsets.UTF_8);
        for (int page = 1; ; page++) {
            JsonNode body = get(path + sep + "page=" + page + "&perPage=" + PAGE_SIZE + filter);
            body.path("items").forEach(items::add);
            if (page >= body.path("total_pages").asInt(0)) {
                return items;
            }
        }
    }

    private JsonNode send(String method, String path, JsonNode body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(config.baseUrl().resolve(path))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + config.apiToken());
        if (body != null) {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                String text = response.body();
                throw new MealieException(response.statusCode(), method + " " + path + " -> " + response.statusCode()
                        + ": " + text.substring(0, Math.min(500, text.length())), null);
            }
            return response.body().isBlank() ? Json.MAPPER.nullNode() : Json.MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new MealieException(-1, method + " " + path + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MealieException(-1, method + " " + path + " interrupted", e);
        }
    }

    private static MealPlanEntry toEntry(JsonNode node) {
        String recipeId = node.path("recipeId").asText(null);
        return new MealPlanEntry(node.path("id").asLong(), LocalDate.parse(node.path("date").asText()),
                MealType.fromMealie(node.path("entryType").asText()), recipeId,
                node.path("recipe").isObject() ? node.path("recipe").path("name").asText() : node.path("title").asText(""));
    }
}
