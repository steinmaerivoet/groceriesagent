package grocery.chat;

/** A text message addressed to the bot: a command, a mention or a reply to one of its messages. */
public record IncomingText(long chatId, long userId, String userName, String text, boolean command) {
}
