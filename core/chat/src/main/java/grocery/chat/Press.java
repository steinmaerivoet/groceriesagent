package grocery.chat;

/**
 * A button press as the channel received it.
 *
 * @param pressId the channel's id for acknowledging the press
 * @param data    the button's callback data
 */
public record Press(String pressId, long chatId, long userId, String userName, String data) {
}
