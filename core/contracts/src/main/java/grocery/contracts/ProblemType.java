package grocery.contracts;

/** Situations the deterministic system cannot resolve on its own (spec §4.7). */
public enum ProblemType {
    EMPTY_CANDIDATE_POOL,
    RECIPE_CLASSIFICATION_REQUIRED,
    UNPARSED_INGREDIENT
}
