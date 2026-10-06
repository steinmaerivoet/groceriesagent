package grocery.contracts;

import java.util.List;

/**
 * What the planner needs to know about a recipe. Read from Mealie for every planning run and never
 * persisted by Grocery Core (spec §3.1).
 *
 * @param rating   the household rating (1–5), or null when unrated
 * @param servings recipe servings, or null when Mealie has none
 * @param foodIds  Mealie Food UUIDs of the parsed ingredients
 */
public record RecipeSummary(
        String id,
        String slug,
        String name,
        List<String> categories,
        List<String> tags,
        Integer rating,
        Double servings,
        List<String> foodIds) {

    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }
}
