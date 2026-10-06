package grocery.contracts;

/**
 * An unresolved problem to surface to the household.
 *
 * @param subject what the problem is about, e.g. a slot or recipe name
 */
public record Problem(ProblemType type, String subject, String message) {
}
