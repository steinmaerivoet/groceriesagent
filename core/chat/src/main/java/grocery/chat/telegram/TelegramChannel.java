package grocery.chat.telegram;

import grocery.chat.ChatChannel;
import grocery.chat.IncomingText;
import grocery.chat.Interaction;
import grocery.chat.Message;
import grocery.chat.Press;
import grocery.chat.Rendering;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ForceReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;
import java.util.function.Consumer;

/**
 * The Telegram adapter behind {@link ChatChannel} (spec §5.1–5.4): official Bot API through the
 * telegrambots library, updates by long polling. All Telegram types stay inside this package.
 */
public final class TelegramChannel implements ChatChannel, LongPollingUpdateConsumer {

    private static final int SEND_ATTEMPTS = 3;

    private final TelegramClient client;
    private final long chatId;
    private final String botUsername;
    private Consumer<Press> presses = p -> { };
    private Consumer<IncomingText> texts = t -> { };

    public TelegramChannel(TelegramClient client, long chatId, String botUsername) {
        this.client = client;
        this.chatId = chatId;
        this.botUsername = botUsername;
    }

    public void onPress(Consumer<Press> handler) {
        this.presses = handler;
    }

    public void onText(Consumer<IncomingText> handler) {
        this.texts = handler;
    }

    @Override
    public String post(Message message, Interaction interaction) {
        Rendering.Rendered r = Rendering.render(message, interaction);
        ReplyKeyboard markup = r.forceReply() ? ForceReplyKeyboard.builder().forceReply(true).build() : keyboard(r);
        SendMessage send = SendMessage.builder()
                .chatId(chatId)
                .text(r.html())
                .parseMode("HTML")
                .disableNotification(r.silent())
                .replyMarkup(markup)
                .build();
        return String.valueOf(withRetry(() -> client.execute(send)).getMessageId());
    }

    @Override
    public void refresh(Interaction interaction) {
        Rendering.Rendered r = Rendering.render(interaction.message(), interaction);
        EditMessageText edit = EditMessageText.builder()
                .chatId(chatId)
                .messageId(Integer.parseInt(interaction.messageId()))
                .text(r.html())
                .parseMode("HTML")
                .replyMarkup(keyboard(r))
                .build();
        withRetry(() -> client.execute(edit));
    }

    @Override
    public void acknowledge(String pressId, String text) {
        withRetry(() -> client.execute(AnswerCallbackQuery.builder().callbackQueryId(pressId).text(text).build()));
    }

    /** Called by the long-polling session; updates are handled one by one, in order. */
    @Override
    public void consume(List<Update> updates) {
        updates.forEach(this::handle);
    }

    @Override
    public void close() {
    }

    /** Routing only, no decisions: those belong to the interaction service. */
    void handle(Update update) {
        if (update.hasCallbackQuery()) {
            CallbackQuery q = update.getCallbackQuery();
            long fromChat = q.getMessage() == null ? 0 : q.getMessage().getChatId();
            presses.accept(new Press(q.getId(), fromChat, q.getFrom().getId(), name(q.getFrom()), q.getData()));
            return;
        }
        if (update.hasMessage() && update.getMessage().hasText()) {
            var m = update.getMessage();
            boolean command = m.isCommand();
            boolean mention = botUsername != null && m.getText().contains("@" + botUsername);
            boolean replyToBot = m.getReplyToMessage() != null && m.getReplyToMessage().getFrom() != null
                    && Boolean.TRUE.equals(m.getReplyToMessage().getFrom().getIsBot());
            // Spec §5.2: everything else in the group is ignored and never reaches an LLM.
            if (command || mention || replyToBot) {
                texts.accept(new IncomingText(m.getChatId(), m.getFrom().getId(), name(m.getFrom()), m.getText(), command));
            }
        }
    }

    private static InlineKeyboardMarkup keyboard(Rendering.Rendered r) {
        List<InlineKeyboardRow> rows = r.keyboard().stream()
                .map(row -> new InlineKeyboardRow(row.stream().map(TelegramChannel::button).toList()))
                .toList();
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static InlineKeyboardButton button(Rendering.Button b) {
        return b.url() != null
                ? InlineKeyboardButton.builder().text(b.label()).url(b.url()).build()
                : InlineKeyboardButton.builder().text(b.label()).callbackData(b.callbackData()).build();
    }

    private static String name(User user) {
        return user.getFirstName() != null ? user.getFirstName() : user.getUserName();
    }

    private interface TelegramCall<T> {
        T run() throws TelegramApiException;
    }

    /**
     * Retries briefly when Telegram is unreachable. The workflow itself waits in its persisted
     * state, so a message that still fails is re-sent by the next workflow step (spec §7.3).
     */
    private static <T> T withRetry(TelegramCall<T> call) {
        TelegramApiException last = null;
        for (int attempt = 1; attempt <= SEND_ATTEMPTS; attempt++) {
            try {
                return call.run();
            } catch (TelegramApiException e) {
                last = e;
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new IllegalStateException("Telegram call failed: " + (last == null ? "interrupted" : last.getMessage()), last);
    }
}
