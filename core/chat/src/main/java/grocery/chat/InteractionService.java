package grocery.chat;

import grocery.chat.Interaction.Status;
import grocery.chat.Message.Checklist;
import grocery.chat.Message.Choice;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Turns button presses into answers without an LLM (spec §5.4): checks the chat and the user,
 * rejects outdated presses, applies each press exactly once and tells the workflow.
 */
public final class InteractionService {

    /** What a press led to; mostly for tests and logs. */
    public enum Outcome { IGNORED, REJECTED, STALE, ALREADY_ANSWERED, TOGGLED, ANSWERED }

    private static final int MAX_ATTEMPTS = 5;

    private final InteractionStore store;
    private final ChatChannel channel;
    private final AccessPolicy access;
    private final WorkflowView workflow;
    private final Consumer<Answer> onAnswer;

    public InteractionService(InteractionStore store, ChatChannel channel, AccessPolicy access, WorkflowView workflow,
                              Consumer<Answer> onAnswer) {
        this.store = store;
        this.channel = channel;
        this.access = access;
        this.workflow = workflow;
        this.onAnswer = onAnswer;
    }

    /** Posts a question (a {@link Choice}, {@link Checklist} or free-text prompt) and remembers it. */
    public Interaction ask(String runId, String state, Message message) {
        Set<String> preselected = message instanceof Checklist c
                ? c.items().stream().filter(Message.Item::preselected).map(Message.Item::id)
                .collect(Collectors.toCollection(LinkedHashSet::new))
                : Set.of();
        Interaction created = store.create(runId, state, message, preselected);
        store.attachMessageId(created.id(), channel.post(message, created));
        return store.find(created.id()).orElseThrow();
    }

    /** Posts information that needs no answer. */
    public void tell(Message message) {
        channel.post(message, null);
    }

    /** Marks every open interaction that no longer matches the workflow as outdated and removes its buttons. */
    public void expireOutdated() {
        for (Interaction i : store.findOpen()) {
            if (!workflow.isCurrent(i.runId(), i.state())) {
                store.update(with(i, i.selected(), Status.STALE, null, null)).ifPresent(channel::refresh);
            }
        }
    }

    public Outcome press(Press press) {
        if (!access.isHouseholdChat(press.chatId())) {
            return Outcome.IGNORED;
        }
        if (!access.isAllowed(press.userId())) {
            channel.acknowledge(press.pressId(), "Sorry, only household members can answer.");
            return Outcome.REJECTED;
        }
        Optional<Callback> callback = Callback.decode(press.data());
        if (callback.isEmpty()) {
            channel.acknowledge(press.pressId(), "This button is no longer active.");
            return Outcome.STALE;
        }
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Optional<Interaction> found = store.find(callback.get().interactionId());
            if (found.isEmpty()) {
                channel.acknowledge(press.pressId(), "This button is no longer active.");
                return Outcome.STALE;
            }
            Interaction i = found.get();
            if (i.status() == Status.ANSWERED) {
                channel.acknowledge(press.pressId(), "Already answered by " + i.answeredBy() + ".");
                return Outcome.ALREADY_ANSWERED;
            }
            if (i.status() == Status.STALE || !workflow.isCurrent(i.runId(), i.state())) {
                if (i.status() == Status.OPEN) {
                    store.update(with(i, i.selected(), Status.STALE, null, null)).ifPresent(channel::refresh);
                }
                channel.acknowledge(press.pressId(), "This question is outdated.");
                return Outcome.STALE;
            }
            Optional<Outcome> outcome = apply(i, callback.get(), press);
            if (outcome.isPresent()) {
                return outcome.get();
            }
            // Someone else changed the interaction between our read and write: read again.
        }
        channel.acknowledge(press.pressId(), "Busy, please try again.");
        return Outcome.STALE;
    }

    /** @return empty when the optimistic update lost a race and the press must be retried */
    private Optional<Outcome> apply(Interaction i, Callback callback, Press press) {
        String action = callback.action();
        if (!callback.fits(i.message())) {
            channel.acknowledge(press.pressId(), "This button is no longer active.");
            return Optional.of(Outcome.STALE);
        }
        if (i.message() instanceof Choice choice && action.startsWith("o")) {
            Message.Option option = choice.options().get(callback.index());
            return answer(i, i.selected(), option.label(), press, option.id());
        }
        if (i.message() instanceof Checklist checklist && action.startsWith("t")) {
            String itemId = checklist.items().get(callback.index()).id();
            Set<String> selected = new LinkedHashSet<>(i.selected());
            if (!selected.remove(itemId)) {
                selected.add(itemId);
            }
            Optional<Interaction> saved = store.update(with(i, selected, Status.OPEN, null, null));
            saved.ifPresent(s -> {
                channel.refresh(s);
                channel.acknowledge(press.pressId(), "");
            });
            return saved.map(s -> Outcome.TOGGLED);
        }
        if (i.message() instanceof Checklist && action.equals("c")) {
            return answer(i, i.selected(), "Confirmed", press, null);
        }
        channel.acknowledge(press.pressId(), "This button is no longer active.");
        return Optional.of(Outcome.STALE);
    }

    private Optional<Outcome> answer(Interaction i, Set<String> selected, String outcome, Press press, String optionId) {
        Optional<Interaction> saved = store.update(with(i, selected, Status.ANSWERED, press.userName(), outcome));
        saved.ifPresent(s -> {
            channel.refresh(s);
            channel.acknowledge(press.pressId(), outcome);
            onAnswer.accept(new Answer(s.id(), s.runId(), s.state(), optionId, s.selected(), press.userId(), press.userName()));
        });
        return saved.map(s -> Outcome.ANSWERED);
    }

    private static Interaction with(Interaction i, Set<String> selected, Status status, String answeredBy, String outcome) {
        return new Interaction(i.id(), i.runId(), i.state(), i.message(), selected, status, i.messageId(), answeredBy, outcome,
                i.version(), i.createdAt());
    }
}
