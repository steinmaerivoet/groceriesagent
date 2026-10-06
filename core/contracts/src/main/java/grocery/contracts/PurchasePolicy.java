package grocery.contracts;

/** How a Food ends up on the shopping list (spec §4.2.1). */
public enum PurchasePolicy {
    /** Add the required quantity automatically. */
    AUTO,
    /** Ask the household whether it needs to be bought. */
    CHECK,
    /** Decide from history whether it is likely needed (Phase 2; treated as CHECK before that). */
    PREDICT
}
