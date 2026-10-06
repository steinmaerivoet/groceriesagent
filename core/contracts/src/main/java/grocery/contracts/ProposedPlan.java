package grocery.contracts;

import java.util.List;

/**
 * The planner's output for one planning run (input of shopping-list generation and chat).
 *
 * @param weekTerms week-level score contributions, such as a missed weekly target
 */
public record ProposedPlan(
        String runId,
        List<PlannedSlot> slots,
        double totalScore,
        List<ScoreTerm> weekTerms,
        List<Problem> problems) {
}
