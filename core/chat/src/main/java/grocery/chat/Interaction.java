package grocery.chat;

import java.time.Instant;
import java.util.Set;

/**
 * A persisted message that awaits an answer (the {@code ChatInteraction} of spec §3.5). Button
 * presses carry only a reference to it, never the payload (Telegram allows 64 bytes).
 *
 * @param runId     the planning run it belongs to
 * @param state     the workflow state it was asked in; once the workflow moves on, it is stale
 * @param selected  for a checklist: the item ids currently ticked (shared by the whole group)
 * @param messageId the channel's reference to the posted message
 * @param version   optimistic-lock counter, bumped on every change
 */
public record Interaction(
        long id,
        String runId,
        String state,
        Message message,
        Set<String> selected,
        Status status,
        String messageId,
        String answeredBy,
        String outcome,
        long version,
        Instant createdAt) {

    public enum Status { OPEN, ANSWERED, STALE }
}
