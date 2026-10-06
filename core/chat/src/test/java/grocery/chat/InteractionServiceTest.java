package grocery.chat;

import grocery.chat.InteractionService.Outcome;
import grocery.chat.Message.Checklist;
import grocery.chat.Message.Choice;
import grocery.chat.Message.Item;
import grocery.chat.Message.Option;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class InteractionServiceTest {

    private static final long GROUP = -100L;
    private static final long STEIN = 1L;
    private static final long PARTNER = 2L;
    private static final long STRANGER = 99L;

    private final InteractionStore store = new InteractionStore("jdbc:sqlite::memory:");
    private final RecordingChannel channel = new RecordingChannel();
    private final List<Answer> answers = Collections.synchronizedList(new ArrayList<>());
    private String currentState = "MEAL_PLAN_PROPOSED";
    private final InteractionService service = new InteractionService(store, channel,
            new AccessPolicy(GROUP, Set.of(STEIN, PARTNER)), (run, state) -> state.equals(currentState), answers::add);

    private static final Choice PROPOSAL = new Choice("Next week is planned.",
            List.of(new Option("ok", "Looks good"), new Option("change", "Change something")), null);

    @AfterEach
    void close() throws Exception {
        store.close();
    }

    private Press press(long user, Interaction i, Callback callback) {
        return new Press("p", GROUP, user, user == STEIN ? "Stein" : "Partner", callback.encode());
    }

    @Test
    void aChoiceIsAnsweredOnceAndTheMessageShowsWhoAnswered() {
        Interaction asked = service.ask("2026-W42", currentState, PROPOSAL);

        assertThat(service.press(press(STEIN, asked, Callback.option(asked.id(), 0)))).isEqualTo(Outcome.ANSWERED);
        assertThat(service.press(press(PARTNER, asked, Callback.option(asked.id(), 1)))).isEqualTo(Outcome.ALREADY_ANSWERED);

        assertThat(answers).singleElement().satisfies(a -> {
            assertThat(a.optionId()).isEqualTo("ok");
            assertThat(a.userName()).isEqualTo("Stein");
        });
        Interaction shown = channel.lastRefresh();
        assertThat(shown.status()).isEqualTo(Interaction.Status.ANSWERED);
        assertThat(Rendering.render(shown.message(), shown).html()).contains("Stein chose “Looks good”");
        assertThat(Rendering.render(shown.message(), shown).keyboard()).isEmpty();
    }

    @Test
    void simultaneousPressesCountOnlyOnce() throws Exception {
        Interaction asked = service.ask("2026-W42", currentState, PROPOSAL);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Outcome>> outcomes = new ArrayList<>();
        for (int n = 0; n < 16; n++) {
            long user = n % 2 == 0 ? STEIN : PARTNER;
            outcomes.add(pool.submit(() -> service.press(press(user, asked, Callback.option(asked.id(), 0)))));
        }
        pool.shutdown();
        List<Outcome> results = new ArrayList<>();
        for (Future<Outcome> f : outcomes) {
            results.add(f.get());
        }

        assertThat(results).containsOnlyOnce(Outcome.ANSWERED);
        assertThat(answers).hasSize(1);
    }

    @Test
    void othersChatsAreIgnoredAndStrangersRejected() {
        Interaction asked = service.ask("2026-W42", currentState, PROPOSAL);
        String data = Callback.option(asked.id(), 0).encode();

        assertThat(service.press(new Press("p", 12345L, STEIN, "Stein", data))).isEqualTo(Outcome.IGNORED);
        assertThat(service.press(new Press("p", GROUP, STRANGER, "Someone", data))).isEqualTo(Outcome.REJECTED);

        assertThat(answers).isEmpty();
        assertThat(store.find(asked.id()).orElseThrow().status()).isEqualTo(Interaction.Status.OPEN);
    }

    @Test
    void outdatedQuestionsAreRejectedAndLoseTheirButtons() {
        Interaction asked = service.ask("2026-W42", currentState, PROPOSAL);
        currentState = "STOCK_CONFIRMATION_REQUIRED";

        assertThat(service.press(press(STEIN, asked, Callback.option(asked.id(), 0)))).isEqualTo(Outcome.STALE);

        assertThat(answers).isEmpty();
        Interaction shown = channel.lastRefresh();
        assertThat(shown.status()).isEqualTo(Interaction.Status.STALE);
        assertThat(Rendering.render(shown.message(), shown).keyboard()).isEmpty();
    }

    @Test
    void expiringRemovesButtonsFromQuestionsTheWorkflowLeftBehind() {
        Interaction asked = service.ask("2026-W42", currentState, PROPOSAL);
        currentState = "SHOPPING_LIST_FINALIZED";

        service.expireOutdated();

        assertThat(store.find(asked.id()).orElseThrow().status()).isEqualTo(Interaction.Status.STALE);
        assertThat(channel.refreshed).hasSize(1);
    }

    @Test
    void checklistTogglesAreSharedAndConfirmReturnsTheTicks() {
        Checklist stock = new Checklist("A few stock checks",
                List.of(new Item("passata", "Passata ×2", true), new Item("rice", "Rice", false), new Item("oil", "Olive oil", false)),
                "Confirm", null);
        Interaction asked = service.ask("2026-W42", currentState, stock);
        assertThat(asked.selected()).containsExactly("passata");

        service.press(press(STEIN, asked, Callback.toggle(asked.id(), 1)));    // tick rice
        service.press(press(PARTNER, asked, Callback.toggle(asked.id(), 0)));  // untick passata
        service.press(press(PARTNER, asked, Callback.toggle(asked.id(), 2)));  // tick oil
        assertThat(service.press(press(STEIN, asked, Callback.confirm(asked.id())))).isEqualTo(Outcome.ANSWERED);

        assertThat(answers).singleElement().satisfies(a -> assertThat(a.selected()).containsExactlyInAnyOrder("rice", "oil"));
    }

    @Test
    void interactionsSurviveARestart(@TempDir Path dir) throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve("chat.db");
        long id;
        try (InteractionStore first = new InteractionStore(url)) {
            var before = new InteractionService(first, channel, new AccessPolicy(GROUP, Set.of(STEIN)), (r, s) -> true, a -> { });
            id = before.ask("2026-W42", "MEAL_PLAN_PROPOSED", PROPOSAL).id();
        }
        try (InteractionStore second = new InteractionStore(url)) {
            var after = new InteractionService(second, channel, new AccessPolicy(GROUP, Set.of(STEIN)), (r, s) -> true, answers::add);
            assertThat(after.press(new Press("p", GROUP, STEIN, "Stein", Callback.option(id, 0).encode()))).isEqualTo(Outcome.ANSWERED);
        }
        assertThat(answers).hasSize(1);
    }
}
