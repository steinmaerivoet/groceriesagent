package grocery.chat;

import java.util.Set;

/**
 * An accepted answer, handed to the workflow (the {@code InteractionAnswered} event).
 *
 * @param optionId for a choice: the chosen option
 * @param selected for a checklist: the confirmed item ids
 */
public record Answer(long interactionId, String runId, String state, String optionId, Set<String> selected,
                     long userId, String userName) {
}
