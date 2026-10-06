package grocery.chat;

import java.util.Optional;

/**
 * What a button carries: an opaque reference to the interaction plus the pressed action, well
 * within Telegram's 64-byte limit. Actions: {@code o<i>} option i, {@code t<i>} toggle item i,
 * {@code c} confirm a checklist.
 */
public record Callback(long interactionId, String action) {

    public static Callback option(long id, int index) {
        return new Callback(id, "o" + index);
    }

    public static Callback toggle(long id, int index) {
        return new Callback(id, "t" + index);
    }

    public static Callback confirm(long id) {
        return new Callback(id, "c");
    }

    public String encode() {
        return "i" + Long.toString(interactionId, 36) + ":" + action;
    }

    /** Empty for data that is not ours (e.g. an old bot's buttons). */
    public static Optional<Callback> decode(String data) {
        if (data == null || !data.startsWith("i") || !data.contains(":")) {
            return Optional.empty();
        }
        try {
            int colon = data.indexOf(':');
            return Optional.of(new Callback(Long.parseLong(data.substring(1, colon), 36), data.substring(colon + 1)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    int index() {
        return Integer.parseInt(action.substring(1));
    }

    /** False for data that doesn't match the message, e.g. a hand-crafted or out-of-range index. */
    boolean fits(Message message) {
        if (action.equals("c")) {
            return message instanceof Message.Checklist;
        }
        if (!action.matches("[ot]\\d{1,4}")) {
            return false;
        }
        return switch (message) {
            case Message.Choice c -> action.startsWith("o") && index() < c.options().size();
            case Message.Checklist c -> action.startsWith("t") && index() < c.items().size();
            default -> false;
        };
    }
}
