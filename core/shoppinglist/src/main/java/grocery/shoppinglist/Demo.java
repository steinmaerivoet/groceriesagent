package grocery.shoppinglist;

import grocery.contracts.Json;
import grocery.contracts.PlannedSlot;
import grocery.contracts.Problem;
import grocery.contracts.ProposedPlan;
import grocery.contracts.RequiredItem;
import grocery.contracts.ShoppingRequirements;
import grocery.mealie.MealieClient;
import grocery.mealie.MealieConfig;
import grocery.shoppinglist.Reconciliation.Action;
import grocery.shoppinglist.Reconciliation.Create;
import grocery.shoppinglist.Reconciliation.Delete;
import grocery.shoppinglist.Reconciliation.Update;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * POC 3 demo. Run from {@code core/}:
 * <pre>
 *   ./gradlew :shoppinglist:run --args="--plan fixtures/plan-2026-W42.json"                 # show requirements
 *   ./gradlew :shoppinglist:run --args="--plan fixtures/plan-2026-W42.json --yes rice,garlic --write"
 *   ./gradlew :shoppinglist:run --args="--recipes moules-frites,lasagne --servings 4"
 *   ./gradlew :shoppinglist:run --args="--plan fixtures/plan-2026-W42.json --out fixtures/requirements-2026-W42.json"
 * </pre>
 * {@code --yes} answers the stock check by food name ({@code --all-yes} buys everything asked).
 * {@code --write} reconciles the Mealie list "Groceries"; what was written is remembered in
 * {@code .state/managed-items.json} so a re-run updates instead of duplicating.
 */
public final class Demo {

    private Demo() {
    }

    public static void main(String[] args) {
        MealieClient mealie = new MealieClient(MealieConfig.fromEnvironment());
        String runId;
        List<String> recipeIds;
        String planFile = option(args, "--plan", null);
        if (planFile != null) {
            ProposedPlan plan = Json.read(Path.of(planFile), ProposedPlan.class);
            runId = plan.runId();
            recipeIds = plan.slots().stream().map(PlannedSlot::recipeId).filter(Objects::nonNull).toList();
        } else {
            runId = option(args, "--run-id", "manual");
            recipeIds = Arrays.asList(option(args, "--recipes", "").split(","));
        }
        ShoppingSettings settings = ShoppingSettings.defaults()
                .withServings(Double.parseDouble(option(args, "--servings", "2")));

        List<Recipe> recipes = MealieRecipes.load(mealie, recipeIds);
        ShoppingRequirements requirements = new RequirementsBuilder(settings).build(runId, recipes);
        print(recipes, requirements, settings);
        String out = option(args, "--out", null);
        if (out != null) {
            Json.write(Path.of(out), requirements);
            System.out.println("\nRequirements written to " + Path.of(out).toAbsolutePath().normalize());
        }

        if (Arrays.asList(args).contains("--write")) {
            Set<String> buy = answers(args, requirements);
            List<DesiredItem> desired = DesiredItems.from(requirements, buy);
            MealieShoppingList list = new MealieShoppingList(mealie, "Groceries");
            ManagedItemStore store = new ManagedItemStore(Path.of(".state/managed-items.json"));
            Map<String, WrittenItem> written = store.load();
            Reconciliation reconciliation = Reconciler.reconcile(desired, list.items(), written);
            MealieShoppingList.Result result = list.apply(reconciliation, written, runId);
            store.save(result.written());

            System.out.printf("%nWrote the Mealie list 'Groceries' (%s):%n", mealie.baseUrl());
            if (reconciliation.actions().isEmpty()) {
                System.out.println("    nothing to change");
            }
            reconciliation.actions().forEach(a -> System.out.println("    " + describe(a)));
            reconciliation.notes().forEach(n -> System.out.println("    note: " + n));
            result.messages().forEach(m -> System.out.println("    warning: " + m));
        }
    }

    private static Set<String> answers(String[] args, ShoppingRequirements requirements) {
        if (Arrays.asList(args).contains("--all-yes")) {
            return requirements.checkQuestions().stream().map(RequiredItem::foodId).collect(Collectors.toSet());
        }
        Set<String> names = new HashSet<>(Arrays.asList(option(args, "--yes", "").split(",")));
        return requirements.checkQuestions().stream().filter(q -> names.contains(q.foodName()))
                .map(RequiredItem::foodId).collect(Collectors.toSet());
    }

    private static void print(List<Recipe> recipes, ShoppingRequirements r, ShoppingSettings settings) {
        System.out.printf("Shopping list for %s: %d recipes scaled to %s servings%n", r.runId(), recipes.size(),
                fmt(settings.householdServings()));
        recipes.forEach(x -> System.out.println("    " + x.name() + (x.servings() == null ? "" : " (serves " + fmt(x.servings()) + ")")));
        System.out.printf("%nAUTO (added without asking): %d items%n", r.autoItems().size());
        r.autoItems().forEach(i -> System.out.println("    " + line(i)));
        System.out.printf("%nStock check (CHECK, asked in one checklist): %d questions%n", r.checkQuestions().size());
        r.checkQuestions().forEach(i -> System.out.println("    [ ] " + line(i)));
        System.out.printf("%nAssumed in stock (STOCKED, not asked): %s%n",
                r.assumedInStock().stream().map(RequiredItem::foodName).collect(Collectors.joining(", ")));
        if (!r.noteItems().isEmpty()) {
            System.out.println("\nNotes (ingredients without a food):");
            r.noteItems().forEach(n -> System.out.println("    " + n.text() + "  (" + n.recipe() + ")"));
        }
        if (!r.problems().isEmpty()) {
            System.out.println("\nProblems:");
            r.problems().stream().map(Problem::message).forEach(m -> System.out.println("    " + m));
        }
    }

    private static String line(RequiredItem i) {
        String amount = i.quantity() == null ? "" : fmt(i.quantity()) + (i.unitName() == null ? " " : " " + i.unitName() + " ");
        return "%-34s %-16s %s".formatted(amount + i.foodName(), i.labelName(), String.join(", ", i.recipes()));
    }

    private static String describe(Action action) {
        return switch (action) {
            case Create c -> "added    " + c.item().label() + (c.item().quantity() == null ? "" : " × " + fmt(c.item().quantity()));
            case Update u -> "updated  " + u.was().label() + " " + fmt(u.was().quantity()) + " → " + fmt(u.item().quantity());
            case Delete d -> "removed  " + d.was().label() + " (no longer needed)";
        };
    }

    private static String fmt(Double d) {
        if (d == null) {
            return "";
        }
        return d == Math.rint(d) ? String.valueOf(d.longValue()) : String.valueOf(d);
    }

    private static String option(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }
}
