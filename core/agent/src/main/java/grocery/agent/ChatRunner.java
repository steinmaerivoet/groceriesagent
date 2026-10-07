package grocery.agent;

import grocery.chat.AccessPolicy;
import grocery.chat.telegram.TelegramChannel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Starts the conversation: in the Telegram group when a bot token is configured, otherwise (or
 * with the {@code console} argument) in the terminal. {@code grocery.agent.channel=none} starts
 * nothing, for tests.
 */
@Component
class ChatRunner implements ApplicationRunner {

    private final GroceryAssistant assistant;
    private final String channel;
    private final String token;
    private final String chatId;
    private final String allowedUserIds;

    ChatRunner(GroceryAssistant assistant,
               @Value("${grocery.agent.channel:auto}") String channel,
               @Value("${TELEGRAM_BOT_TOKEN:}") String token,
               @Value("${TELEGRAM_CHAT_ID:}") String chatId,
               @Value("${TELEGRAM_ALLOWED_USER_IDS:}") String allowedUserIds) {
        this.assistant = assistant;
        this.channel = channel;
        this.token = token;
        this.chatId = chatId;
        this.allowedUserIds = allowedUserIds;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String mode = args.getNonOptionArgs().contains("console") ? "console" : channel;
        if (mode.equals("auto")) {
            mode = token.isBlank() ? "console" : "telegram";
        }
        switch (mode) {
            case "telegram" -> runInTelegram();
            case "console" -> runInConsole();
            case "none" -> { }
            default -> throw new IllegalStateException("Unknown grocery.agent.channel: " + mode);
        }
    }

    private void runInConsole() throws Exception {
        System.out.println("Console mode: ask something like 'geef mij een recept met vis', or 'quit'.");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        System.out.print("> ");
        for (String line; (line = in.readLine()) != null && !line.strip().equals("quit"); ) {
            if (!line.isBlank()) {
                try {
                    System.out.println(assistant.answer("console", line.strip()));
                } catch (RuntimeException e) {
                    System.out.println("(agent failed: " + e.getMessage() + ")");
                }
            }
            System.out.print("> ");
        }
    }

    @SuppressWarnings("try") // the long-polling application only needs closing on shutdown
    private void runInTelegram() throws Exception {
        long household = Long.parseLong(require("TELEGRAM_CHAT_ID", chatId));
        Set<Long> allowed = Arrays.stream(require("TELEGRAM_ALLOWED_USER_IDS", allowedUserIds).split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).map(Long::parseLong).collect(Collectors.toSet());
        OkHttpTelegramClient client = new OkHttpTelegramClient(token);
        String botUsername = client.execute(new GetMe()).getUserName();
        TelegramChannel telegram = new TelegramChannel(client, household, botUsername);
        AssistantBot bot = new AssistantBot(new AccessPolicy(household, allowed), telegram, (conversation, question) -> {
            typing(client, household);
            return assistant.answer(conversation, question);
        }, botUsername);
        telegram.onText(bot::handle);
        try (TelegramBotsLongPollingApplication app = new TelegramBotsLongPollingApplication()) {
            app.registerBot(token, telegram);
            System.out.println("Bot @" + botUsername + " is polling for chat " + household + "; Ctrl+C to stop.");
            Thread.currentThread().join();
        }
    }

    /** Shows "typing…" while the model thinks; purely cosmetic, so failures are ignored. */
    private static void typing(OkHttpTelegramClient client, long chatId) {
        try {
            client.execute(SendChatAction.builder().chatId(chatId).action(ActionType.TYPING.toString()).build());
        } catch (TelegramApiException e) {
            // ignore
        }
    }

    private static String require(String key, String value) {
        if (value.isBlank()) {
            throw new IllegalStateException(key + " is not set (environment or .env)");
        }
        return value;
    }
}
