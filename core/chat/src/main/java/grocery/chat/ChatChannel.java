package grocery.chat;

/**
 * Port to the chat channel (spec §5.4). Telegram is the only adapter; another channel would be a
 * second implementation. Buttons carry {@link Callback#encode() opaque callback data}.
 */
public interface ChatChannel {

    /** Posts a message to the household group and returns the channel's message reference. */
    String post(Message message, Interaction interaction);

    /** Re-renders an interaction in place, e.g. new checklist ticks or the final outcome. */
    void refresh(Interaction interaction);

    /** Short pop-up answer to a button press (Telegram's callback answer). */
    void acknowledge(String pressId, String text);
}
