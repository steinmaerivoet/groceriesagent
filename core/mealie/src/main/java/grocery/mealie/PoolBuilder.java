package grocery.mealie;

import grocery.contracts.Slot;
import grocery.contracts.SlotPool;

import java.util.List;

/** Builds a slot's candidate pool exactly as Mealie's random button would see it (spec §3.4.3). */
public class PoolBuilder {

    private final MealieClient mealie;
    private final List<PlannerRule> rules;

    public PoolBuilder(MealieClient mealie) {
        this.mealie = mealie;
        this.rules = mealie.plannerRules();
    }

    public SlotPool pool(Slot slot) {
        List<PlannerRule> matching = RuleMatcher.matching(rules, slot);
        String filter = RuleMatcher.combinedFilter(matching);
        List<String> recipeIds = mealie.recipes(filter).stream().map(r -> r.path("id").asText()).sorted().toList();
        return new SlotPool(slot, matching.stream().map(PlannerRule::id).toList(), filter, recipeIds);
    }

    public List<PlannerRule> rules() {
        return rules;
    }
}
