package grocery.contracts;

/** One contribution to a slot's score, kept so the choice can be explained (spec §4.1.4 step 5). */
public record ScoreTerm(String term, double value, String reason) {
}
