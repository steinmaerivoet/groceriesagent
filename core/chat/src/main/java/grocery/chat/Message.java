package grocery.chat;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * Channel-independent interaction primitives (spec §5.3). The workflow and the agent only speak in
 * these; the Telegram adapter decides how they look.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(Message.Notice.class),
        @JsonSubTypes.Type(Message.Choice.class),
        @JsonSubTypes.Type(Message.Checklist.class),
        @JsonSubTypes.Type(Message.Summary.class),
        @JsonSubTypes.Type(Message.Link.class),
        @JsonSubTypes.Type(Message.FreeText.class)})
public sealed interface Message {

    /** Information, no answer required. Silent messages don't notify the group. */
    record Notice(String text, boolean silent) implements Message {
    }

    /** Pick one option, e.g. [Looks good] [Change something]. */
    record Choice(String text, List<Option> options, Link link) implements Message {
    }

    /** Toggle several items, then confirm. Preselected items start ticked. */
    record Checklist(String text, List<Item> items, String confirmLabel, Link link) implements Message {
    }

    /** Amounts and tables, shown as an aligned monospace block. */
    record Summary(String title, List<List<String>> rows) implements Message {
    }

    /** Open the matching Mealie page. */
    record Link(String text, String label, String url) implements Message {
    }

    /** Ask an open question; the answer is the reply to this message. */
    record FreeText(String question) implements Message {
    }

    record Option(String id, String label) {
    }

    record Item(String id, String label, boolean preselected) {
    }
}
