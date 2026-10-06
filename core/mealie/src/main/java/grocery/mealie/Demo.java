package grocery.mealie;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import grocery.contracts.Json;
import grocery.contracts.MealPlanEntry;
import grocery.contracts.MealType;
import grocery.contracts.PlanningSnapshot;
import grocery.contracts.PlanningWeek;
import grocery.contracts.RecipeSummary;
import grocery.contracts.SlotPool;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * POC 1 demo. Run from {@code core/}:
 * <pre>
 *   ./gradlew :mealie:run --args="pools [--week 2026-W42] [--draws 20]"
 *   ./gradlew :mealie:run --args="snapshot [--week 2026-W42] [--out fixtures/snapshot-2026-W42.json]"
 *   ./gradlew :mealie:run --args="probe"
 * </pre>
 */
public final class Demo {

    private Demo() {
    }

    public static void main(String[] args) {
        String command = args.length > 0 ? args[0] : "pools";
        PlanningWeek week = PlanningWeek.parse(option(args, "--week", PlanningWeek.after(LocalDate.now()).id()));
        MealieClient mealie = new MealieClient(MealieConfig.fromEnvironment());
        switch (command) {
            case "pools" -> pools(mealie, week, Integer.parseInt(option(args, "--draws", "20")));
            case "snapshot" -> snapshot(mealie, week, Path.of(option(args, "--out", "fixtures/snapshot-" + week.id() + ".json")));
            case "probe" -> Probe.run(mealie, System.out);
            default -> {
                System.err.println("Unknown command " + command + "; use pools, snapshot or probe");
                System.exit(2);
            }
        }
    }

    private static void pools(MealieClient mealie, PlanningWeek week, int draws) {
        PoolBuilder builder = new PoolBuilder(mealie);
        Map<String, PlannerRule> rules = builder.rules().stream().collect(Collectors.toMap(PlannerRule::id, Function.identity()));
        System.out.printf("Candidate pools for %s (%d planner rules in Mealie)%n%n", week, rules.size());
        boolean allOk = true;
        for (var slot : week.slots(List.of(MealType.DINNER))) {
            SlotPool pool = builder.pool(slot);
            PoolCheck.Result check = PoolCheck.check(mealie, pool, draws);
            allOk &= check.ok();
            System.out.printf("%-26s %2d rules  pool %3d recipes   random button: %s%n", slot, pool.ruleIds().size(),
                    pool.recipeIds().size(), describe(check));
            System.out.println("    filter: " + (pool.queryFilter().isEmpty() ? "(none)" : pool.queryFilter()));
        }
        System.out.println();
        System.out.println(allOk ? "All pools match what Mealie's random button draws from."
                : "MISMATCH: at least one pool differs from Mealie's random button.");
        if (!allOk) {
            System.exit(1);
        }
    }

    private static String describe(PoolCheck.Result r) {
        if (r.pool().recipeIds().isEmpty()) {
            return r.emptyAgreed() ? "also finds nothing (404) OK" : "drew a recipe although our pool is empty!";
        }
        return r.outsidePool().isEmpty()
                ? "%d draws, %d distinct, all inside the pool OK".formatted(r.draws(), r.drawn().size())
                : "drew %d recipes outside the pool: %s".formatted(r.outsidePool().size(), r.outsidePool());
    }

    private static void snapshot(MealieClient mealie, PlanningWeek week, Path out) {
        PlanningSnapshot snapshot = new SnapshotBuilder(mealie, Clock.systemUTC()).build(week, List.of(MealType.DINNER), 4);
        Json.write(out, snapshot);
        Map<String, RecipeSummary> recipes = snapshot.recipes().stream()
                .collect(Collectors.toMap(RecipeSummary::id, Function.identity()));
        System.out.printf("Snapshot for %s written to %s%n", week, out.toAbsolutePath().normalize());
        System.out.printf("  %d slots, %d recipes, %d fixed entries, %d history entries%n", snapshot.pools().size(),
                snapshot.recipes().size(), snapshot.fixedEntries().size(), snapshot.history().size());
        for (MealPlanEntry e : snapshot.fixedEntries()) {
            System.out.printf("  fixed: %s  %s%n", e.slot(), e.recipeId() == null ? e.title() : recipes.get(e.recipeId()).name());
        }
    }

    private static String option(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    /** Checks the Mealie behaviour the spec still lists as unverified (OQ-01, OQ-04). */
    static final class Probe {

        private Probe() {
        }

        static void run(MealieClient mealie, java.io.PrintStream out) {
            List<Runnable> cleanup = new ArrayList<>();
            try {
                // 1. Meal-plan entry CRUD
                JsonNode anyRecipe = mealie.recipes("").getFirst();
                LocalDate farAway = LocalDate.now().plusYears(5);
                MealPlanEntry entry = mealie.createMealPlan(farAway, MealType.DINNER, anyRecipe.path("id").asText());
                cleanup.add(() -> mealie.deleteMealPlan(entry.id()));
                boolean listed = mealie.mealPlans(farAway, farAway).stream().anyMatch(e -> e.id().equals(entry.id()));
                out.println(result(listed, "meal-plan entries can be created, listed by date range and deleted"));

                // 2. Shopping-list item extras
                String listId = mealie.shoppingLists().getFirst().path("id").asText();
                JsonNode food = mealie.foods().stream().filter(f -> f.path("name").asText().equals("leek")).findFirst().orElseThrow();
                ObjectNode managed = item(listId, food, 2, "{\"managed\":true,\"planningRunId\":\"probe\",\"origin\":\"meal-plan\"}");
                JsonNode created = mealie.createShoppingItem(managed).path("createdItems").get(0);
                String createdId = created.path("id").asText();
                cleanup.add(() -> mealie.deleteShoppingItem(createdId));
                String extras = created.path("extras").path("groceries").asText("");
                out.println(result(extras.contains("\"managed\":true"),
                        "shopping-list items keep `groceries` extras (stored as a JSON string): " + extras));

                // 3. Does Mealie merge a second item for the same food into the first?
                ObjectNode userItem = item(listId, food, 1, null);
                JsonNode response = mealie.createShoppingItem(userItem);
                response.path("createdItems").forEach(i -> cleanup.add(() -> mealie.deleteShoppingItem(i.path("id").asText())));
                boolean merged = response.path("createdItems").isEmpty() && !response.path("updatedItems").isEmpty();
                JsonNode mergedInto = response.path("updatedItems").path(0);
                out.println("NOTE  adding a second 'leek' item " + (merged
                        ? "was MERGED into the existing one by Mealie (quantity now %s, extras %s); reconciliation must expect this"
                                .formatted(mergedInto.path("quantity").asText(), mergedInto.path("extras").path("groceries").asText("none"))
                        : "created a separate item; Mealie does not merge on create"));
                List<JsonNode> items = mealie.shoppingItems(listId);
                out.println(result(items.stream().allMatch(i -> i.path("shoppingListId").asText().equals(listId)),
                        "listing items filtered by shopping list works (" + items.size() + " items)"));

                // 4. Food extras are readable
                long withPolicy = mealie.foods().stream().filter(f -> f.path("extras").has("groceries")).count();
                out.println(result(withPolicy > 0, withPolicy + " foods carry a `groceries` extra (per-food policy overrides)"));
            } finally {
                cleanup.reversed().forEach(Runnable::run);
                out.println("(probe data removed again)");
            }
        }

        private static ObjectNode item(String listId, JsonNode food, double quantity, String groceriesExtra) {
            ObjectNode node = Json.MAPPER.createObjectNode()
                    .put("shoppingListId", listId)
                    .put("foodId", food.path("id").asText())
                    .put("quantity", quantity)
                    .put("note", "");
            if (groceriesExtra != null) {
                node.putObject("extras").put("groceries", groceriesExtra);
            }
            return node;
        }

        private static String result(boolean ok, String message) {
            return (ok ? "OK    " : "FAIL  ") + message;
        }
    }
}
