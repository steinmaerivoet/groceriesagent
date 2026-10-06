package grocery.planning;

import grocery.contracts.MealPlanEntry;
import grocery.contracts.PlannedSlot;
import grocery.contracts.PlanningSnapshot;
import grocery.contracts.Problem;
import grocery.contracts.ProblemType;
import grocery.contracts.ProposedPlan;
import grocery.contracts.Slot;
import grocery.contracts.SlotPool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic slot-based heuristic (spec §4.1.4): build pools, fill the most constrained slot
 * first with its best candidate, then improve by replacing and swapping until nothing gets better.
 */
public final class HeuristicMealPlanner implements MealPlanner {

    private static final double EPSILON = 1e-9;

    private final PlannerSettings settings;

    public HeuristicMealPlanner(PlannerSettings settings) {
        this.settings = settings;
    }

    @Override
    public ProposedPlan plan(PlanningSnapshot snapshot) {
        PlanScorer scorer = new PlanScorer(snapshot, settings);
        Map<Slot, String> plan = new TreeMap<>();
        Set<Slot> fixed = new HashSet<>();
        for (MealPlanEntry entry : snapshot.fixedEntries()) {
            plan.put(entry.slot(), entry.recipeId());
            fixed.add(entry.slot());
        }

        // 1. Pools: the slot's Mealie pool, minus recipes already fixed elsewhere in the week.
        Set<String> fixedRecipes = new HashSet<>(plan.values());
        Map<Slot, List<String>> candidates = new LinkedHashMap<>();
        for (SlotPool pool : snapshot.pools()) {
            if (!fixed.contains(pool.slot())) {
                candidates.put(pool.slot(), pool.recipeIds().stream()
                        .filter(id -> !fixedRecipes.contains(id) && scorer.recipe(id) != null)
                        .toList());
            }
        }

        // 2.–3. Most constrained slot first, greedily take the best candidate given the partial plan.
        candidates.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<Slot, List<String>> e) -> e.getValue().size())
                        .thenComparing(Map.Entry::getKey))
                .filter(e -> !e.getValue().isEmpty())
                .forEach(e -> plan.put(e.getKey(), best(scorer, plan, e.getKey(), e.getValue())));

        // 4. Local improvement over the slots we filled.
        improve(scorer, plan, candidates);

        return result(snapshot, scorer, plan, fixed, problems(snapshot, candidates));
    }

    @Override
    public ProposedPlan replace(PlanningSnapshot snapshot, ProposedPlan current, Slot slot) {
        PlanScorer scorer = new PlanScorer(snapshot, settings);
        Map<Slot, String> plan = new TreeMap<>();
        Set<Slot> fixed = new HashSet<>();
        String previous = null;
        for (PlannedSlot s : current.slots()) {
            if (s.slot().equals(slot)) {
                previous = s.recipeId();
            } else if (s.recipeId() != null || s.fixed()) {
                plan.put(s.slot(), s.recipeId());
            }
            if (s.fixed()) {
                fixed.add(s.slot());
            }
        }
        if (fixed.contains(slot)) {
            throw new IllegalArgumentException(slot + " is a fixed entry and is not replaced");
        }
        String excluded = previous;
        List<String> pool = snapshot.pools().stream().filter(p -> p.slot().equals(slot)).findFirst()
                .map(SlotPool::recipeIds).orElse(List.of()).stream()
                .filter(id -> !id.equals(excluded) && scorer.recipe(id) != null)
                .toList();
        List<Problem> problems = new ArrayList<>(current.problems());
        if (pool.isEmpty()) {
            problems.add(new Problem(ProblemType.EMPTY_CANDIDATE_POOL, slot.toString(),
                    "No other recipe matches the planner rules for " + slot));
        } else {
            plan.put(slot, best(scorer, plan, slot, pool));
        }
        return result(snapshot, scorer, plan, fixed, problems);
    }

    private static String best(PlanScorer scorer, Map<Slot, String> plan, Slot slot, List<String> pool) {
        String best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (String candidate : pool) {
            Map<Slot, String> trial = new HashMap<>(plan);
            trial.put(slot, candidate);
            double score = scorer.total(trial);
            if (score > bestScore + EPSILON) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private void improve(PlanScorer scorer, Map<Slot, String> plan, Map<Slot, List<String>> candidates) {
        List<Slot> open = candidates.keySet().stream().filter(plan::containsKey).sorted().toList();
        double current = scorer.total(plan);
        for (int round = 0; round < settings.improvementRounds(); round++) {
            boolean improved = false;
            for (Slot slot : open) {
                String replacement = best(scorer, plan, slot, candidates.get(slot));
                Map<Slot, String> trial = new HashMap<>(plan);
                trial.put(slot, replacement);
                double score = scorer.total(trial);
                if (score > current + EPSILON) {
                    plan.put(slot, replacement);
                    current = score;
                    improved = true;
                }
            }
            for (int i = 0; i < open.size(); i++) {
                for (int j = i + 1; j < open.size(); j++) {
                    Slot a = open.get(i);
                    Slot b = open.get(j);
                    String ra = plan.get(a);
                    String rb = plan.get(b);
                    if (candidates.get(a).contains(rb) && candidates.get(b).contains(ra)) {
                        Map<Slot, String> trial = new HashMap<>(plan);
                        trial.put(a, rb);
                        trial.put(b, ra);
                        double score = scorer.total(trial);
                        if (score > current + EPSILON) {
                            plan.put(a, rb);
                            plan.put(b, ra);
                            current = score;
                            improved = true;
                        }
                    }
                }
            }
            if (!improved) {
                return;
            }
        }
    }

    private List<Problem> problems(PlanningSnapshot snapshot, Map<Slot, List<String>> candidates) {
        List<Problem> problems = new ArrayList<>();
        candidates.forEach((slot, pool) -> {
            if (pool.isEmpty()) {
                SlotPool source = snapshot.pools().stream().filter(p -> p.slot().equals(slot)).findFirst().orElseThrow();
                problems.add(new Problem(ProblemType.EMPTY_CANDIDATE_POOL, slot.toString(),
                        "No recipe matches the planner rules for " + slot
                                + (source.queryFilter().isEmpty() ? "" : " (filter: " + source.queryFilter() + ")")));
            }
        });
        problems.addAll(RecipeClassification.check(snapshot, settings));
        return problems;
    }

    private static ProposedPlan result(PlanningSnapshot snapshot, PlanScorer scorer, Map<Slot, String> plan,
                                       Set<Slot> fixed, List<Problem> problems) {
        PlanScorer.Evaluation evaluation = scorer.evaluate(plan);
        Set<Slot> slots = new TreeSet<>(plan.keySet());
        snapshot.pools().forEach(p -> slots.add(p.slot()));
        List<PlannedSlot> planned = slots.stream()
                .map(s -> new PlannedSlot(s, plan.get(s), fixed.contains(s), round(evaluation.slotScore(s)),
                        evaluation.slotTerms().getOrDefault(s, List.of())))
                .toList();
        return new ProposedPlan(snapshot.runId(), planned, round(evaluation.total()), evaluation.weekTerms(), problems);
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
