package grocery.chat;

import grocery.chat.Message.Checklist;
import grocery.chat.Message.Choice;
import grocery.chat.Message.Item;
import grocery.chat.Message.Link;
import grocery.chat.Message.Notice;
import grocery.chat.Message.Option;
import grocery.chat.Message.Summary;
import grocery.chat.telegram.TelegramChannel;
import grocery.contracts.Json;
import grocery.contracts.PlannedSlot;
import grocery.contracts.PlanningSnapshot;
import grocery.contracts.ProposedPlan;
import grocery.contracts.RecipeSummary;
import grocery.contracts.RequiredItem;
import grocery.contracts.ShoppingRequirements;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.api.methods.GetMe;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * POC 4 demo: the Phase 1 conversation of spec Appendix A.1, driven by the fixture plan and
 * shopping requirements. Run from {@code core/}:
 * <pre>
 *   ./gradlew -q --console=plain :chat:run --args=console   # in the terminal, no bot needed
 *   ./gradlew -q :chat:run                                  # in your Telegram test group (needs TELEGRAM_* in .env)
 * </pre>
 * In the console, type a button number to press it as Stein, {@code 3 as Guest} to press as a
 * member who is not allowlisted, {@code /plan} to post a fresh proposal (older buttons become
 * outdated) and {@code quit} to stop.
 */
public final class Demo {

    static final String PROPOSED = "MEAL_PLAN_PROPOSED";
    static final String STOCK_CHECK = "STOCK_CONFIRMATION_REQUIRED";
    static final String FINALIZED = "SHOPPING_LIST_FINALIZED";

    private final PlanningSnapshot snapshot;
    private final ProposedPlan plan;
    private final ShoppingRequirements requirements;
    private final String mealieUrl;
    private InteractionService service;
    private volatile String state;

    private Demo(PlanningSnapshot snapshot, ProposedPlan plan, ShoppingRequirements requirements, String mealieUrl) {
        this.snapshot = snapshot;
        this.plan = plan;
        this.requirements = requirements;
        this.mealieUrl = mealieUrl;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> env = env();
        String week = option(args, "--week", "2026-W42");
        Demo demo = new Demo(
                Json.read(Path.of("fixtures/snapshot-" + week + ".json"), PlanningSnapshot.class),
                Json.read(Path.of("fixtures/plan-" + week + ".json"), ProposedPlan.class),
                Json.read(Path.of("fixtures/requirements-" + week + ".json"), ShoppingRequirements.class),
                env.getOrDefault("MEALIE_BASE_URL", "http://localhost:9925"));
        Files.createDirectories(Path.of(".state"));
        try (InteractionStore store = new InteractionStore("jdbc:sqlite:.state/chat.db")) {
            if (Arrays.asList(args).contains("console") || env.getOrDefault("TELEGRAM_BOT_TOKEN", "").isBlank()) {
                demo.runInConsole(store);
            } else {
                demo.runInTelegram(store, env);
            }
        }
    }

    // ------------------------------------------------------------------ the conversation

    private void start() {
        state = PROPOSED;
        service.expireOutdated();
        service.ask(plan.runId(), PROPOSED, new Choice(proposalText(),
                List.of(new Option("ok", "Looks good"), new Option("change", "Change something")),
                new Link("Open in Mealie", "Open in Mealie", mealieUrl + "/household/mealplan/planner/view")));
    }

    private void onAnswer(Answer answer) {
        if (answer.state().equals(PROPOSED) && "ok".equals(answer.optionId())) {
            state = STOCK_CHECK;
            service.expireOutdated();
            List<Item> items = requirements.checkQuestions().stream()
                    .map(q -> new Item(q.foodId(), label(q), false))
                    .toList();
            service.ask(plan.runId(), STOCK_CHECK, new Checklist("A few stock checks: tick what we need to buy", items, "Confirm", null));
        } else if (answer.state().equals(PROPOSED)) {
            service.tell(new Message.FreeText("What would you like to change? (Free text goes to the agent, POC 5.)"));
        } else if (answer.state().equals(STOCK_CHECK)) {
            state = FINALIZED;
            int total = requirements.autoItems().size() + answer.selected().size() + requirements.noteItems().size();
            service.tell(new Summary("Shopping list", List.of(
                    List.of("Added automatically", String.valueOf(requirements.autoItems().size())),
                    List.of("From the stock check", String.valueOf(answer.selected().size())),
                    List.of("Total", String.valueOf(total)))));
            service.tell(new Link("Your shopping list is ready in Mealie: " + total + " items.", "Open the list",
                    mealieUrl + "/shopping-lists"));
        }
    }

    private void onText(IncomingText text, AccessPolicy access) {
        if (!access.isHouseholdChat(text.chatId())) {
            return;
        }
        if (!access.isAllowed(text.userId())) {
            service.tell(new Notice("Sorry " + text.userName() + ", only household members can use this bot.", true));
        } else if (text.text().startsWith("/plan")) {
            start();
        } else if (text.text().startsWith("/status")) {
            service.tell(new Notice("Run " + plan.runId() + " is in state " + state + ".", true));
        } else {
            service.tell(new Notice("I can't handle free text yet; that's the agent (POC 5). Try /plan or /status.", true));
        }
    }

    private String proposalText() {
        Map<String, RecipeSummary> recipes = snapshot.recipes().stream()
                .collect(Collectors.toMap(RecipeSummary::id, Function.identity()));
        long fish = countTag(recipes, "Fish");
        long veg = countTag(recipes, "Vegetarian");
        StringBuilder text = new StringBuilder("Next week is planned: %d fish and %d vegetarian meals.\n\n".formatted(fish, veg));
        for (PlannedSlot s : plan.slots()) {
            String day = s.slot().date().getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            RecipeSummary r = recipes.get(s.recipeId());
            text.append(day).append("  ").append(r == null ? "(nothing matches your rules)" : r.name()).append('\n');
        }
        return text.toString().stripTrailing();
    }

    private long countTag(Map<String, RecipeSummary> recipes, String tag) {
        return plan.slots().stream().map(s -> recipes.get(s.recipeId())).filter(r -> r != null && r.hasTag(tag)).count();
    }

    private static String label(RequiredItem q) {
        return q.amountText().isEmpty() ? q.foodName() : q.foodName() + " (" + q.amountText() + ")";
    }

    // ------------------------------------------------------------------ channels

    private void runInConsole(InteractionStore store) throws IOException {
        long household = -100L;
        Map<String, Long> users = new HashMap<>(Map.of("Stein", 1L, "Guest", 99L));
        AccessPolicy access = new AccessPolicy(household, Set.of(1L));
        ConsoleChannel console = new ConsoleChannel(System.out);
        service = new InteractionService(store, console, access, this::isCurrent, this::onAnswer);
        System.out.println("Console mode: type a button number, '<n> as Guest', /plan, /status or quit.");
        start();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        int presses = 0;
        for (String line; (line = in.readLine()) != null && !line.equals("quit"); ) {
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\s+as\\s+");
            String user = parts.length > 1 ? parts[1] : "Stein";
            long userId = users.computeIfAbsent(user, u -> 1000L + users.size());
            if (parts[0].matches("\\d+")) {
                String data = console.button(Integer.parseInt(parts[0]));
                InteractionService.Outcome outcome = service.press(
                        new Press("press-" + ++presses, household, userId, user, data));
                System.out.println("  → " + outcome);
            } else {
                onText(new IncomingText(household, userId, user, parts[0], parts[0].startsWith("/")), access);
            }
        }
    }

    @SuppressWarnings("try") // the long-polling application only needs closing on shutdown
    private void runInTelegram(InteractionStore store, Map<String, String> env) throws Exception {
        String token = env.get("TELEGRAM_BOT_TOKEN");
        long chatId = Long.parseLong(require(env, "TELEGRAM_CHAT_ID"));
        Set<Long> allowed = Arrays.stream(require(env, "TELEGRAM_ALLOWED_USER_IDS").split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).map(Long::parseLong).collect(Collectors.toSet());
        AccessPolicy access = new AccessPolicy(chatId, allowed);
        OkHttpTelegramClient client = new OkHttpTelegramClient(token);
        String botUsername = client.execute(new GetMe()).getUserName();
        TelegramChannel telegram = new TelegramChannel(client, chatId, botUsername);
        service = new InteractionService(store, telegram, access, this::isCurrent, this::onAnswer);
        telegram.onPress(service::press);
        telegram.onText(t -> onText(t, access));
        try (TelegramBotsLongPollingApplication app = new TelegramBotsLongPollingApplication()) {
            app.registerBot(token, telegram);
            System.out.println("Bot @" + botUsername + " is polling. Posting the proposal to chat " + chatId + "; Ctrl+C to stop.");
            start();
            Thread.currentThread().join();
        }
    }

    private boolean isCurrent(String runId, String interactionState) {
        return plan.runId().equals(runId) && interactionState.equals(state);
    }

    // ------------------------------------------------------------------ configuration

    private static Map<String, String> env() throws IOException {
        Map<String, String> values = new HashMap<>();
        Path dotEnv = Path.of(".env");
        if (Files.exists(dotEnv)) {
            for (String line : Files.readAllLines(dotEnv)) {
                int eq = line.indexOf('=');
                if (!line.startsWith("#") && eq > 0) {
                    values.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
                }
            }
        }
        System.getenv().forEach((k, v) -> {
            if (!v.isBlank()) {
                values.put(k, v);
            }
        });
        return values;
    }

    private static String require(Map<String, String> env, String key) {
        String value = env.getOrDefault(key, "");
        if (value.isBlank()) {
            throw new IllegalStateException(key + " is not set in .env");
        }
        return value;
    }

    private static String option(String[] args, String name, String fallback) {
        List<String> list = new ArrayList<>(Arrays.asList(args));
        int i = list.indexOf(name);
        return i >= 0 && i + 1 < list.size() ? list.get(i + 1) : fallback;
    }
}
