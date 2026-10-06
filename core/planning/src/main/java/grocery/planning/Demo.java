package grocery.planning;

import grocery.contracts.Json;
import grocery.contracts.PlannedSlot;
import grocery.contracts.PlanningSnapshot;
import grocery.contracts.Problem;
import grocery.contracts.ProposedPlan;
import grocery.contracts.RecipeSummary;
import grocery.contracts.ScoreTerm;
import grocery.contracts.Slot;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * POC 2 demo. Run from {@code core/}:
 * <pre>
 *   ./gradlew :planning:run --args="fixtures/snapshot-2026-W42.json [--run-id 2026-W42] [--replace 2026-10-14] [--out fixtures/plan-2026-W42.json]"
 * </pre>
 */
public final class Demo {

    private Demo() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("usage: <snapshot.json> [--run-id ID] [--replace YYYY-MM-DD] [--out plan.json]");
            System.exit(2);
        }
        PlanningSnapshot snapshot = Json.read(Path.of(args[0]), PlanningSnapshot.class);
        String runId = option(args, "--run-id", null);
        if (runId != null) {
            snapshot = new PlanningSnapshot(runId, snapshot.weekStart(), snapshot.weekEnd(), snapshot.capturedAt(),
                    snapshot.pools(), snapshot.fixedEntries(), snapshot.history(), snapshot.recipes());
        }
        MealPlanner planner = new HeuristicMealPlanner(PlannerSettings.defaults());
        ProposedPlan plan = planner.plan(snapshot);
        print(snapshot, plan);

        String replace = option(args, "--replace", null);
        if (replace != null) {
            Slot slot = plan.slots().stream().map(PlannedSlot::slot)
                    .filter(s -> s.date().equals(LocalDate.parse(replace))).findFirst().orElseThrow();
            plan = planner.replace(snapshot, plan, slot);
            System.out.println("\nAfter replacing " + slot + ":\n");
            print(snapshot, plan);
        }
        String out = option(args, "--out", null);
        if (out != null) {
            Json.write(Path.of(out), plan);
            System.out.println("\nPlan written to " + Path.of(out).toAbsolutePath().normalize());
        }
    }

    private static void print(PlanningSnapshot snapshot, ProposedPlan plan) {
        Map<String, RecipeSummary> recipes = snapshot.recipes().stream()
                .collect(Collectors.toMap(RecipeSummary::id, Function.identity()));
        System.out.printf("Plan %s  (total score %.2f)%n%n", plan.runId(), plan.totalScore());
        for (PlannedSlot s : plan.slots()) {
            RecipeSummary r = recipes.get(s.recipeId());
            String name = r == null ? "(empty)" : r.name();
            System.out.printf("%-26s %-42s %6.2f%s%n", s.slot(), name, s.score(), s.fixed() ? "  [fixed]" : "");
            if (r != null) {
                System.out.println("    tags: " + String.join(", ", r.tags()));
            }
            for (ScoreTerm t : s.explanation()) {
                System.out.printf("    %+6.2f  %-17s %s%n", t.value(), t.term(), t.reason());
            }
        }
        if (!plan.weekTerms().isEmpty()) {
            System.out.println("\nWeek-level penalties:");
            plan.weekTerms().forEach(t -> System.out.printf("    %+6.2f  %-17s %s%n", t.value(), t.term(), t.reason()));
        }
        if (!plan.problems().isEmpty()) {
            System.out.println("\nProblems to resolve in Mealie:");
            for (Problem p : plan.problems()) {
                System.out.println("    " + p.type() + ": " + p.message());
            }
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
}
