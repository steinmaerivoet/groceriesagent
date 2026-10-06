package grocery.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** A channel that remembers what it was asked to do. */
class RecordingChannel implements ChatChannel {

    final List<Message> posted = new ArrayList<>();
    final List<Interaction> refreshed = new ArrayList<>();
    final List<String> acknowledgements = new ArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();

    @Override
    public synchronized String post(Message message, Interaction interaction) {
        posted.add(message);
        return String.valueOf(ids.incrementAndGet());
    }

    @Override
    public synchronized void refresh(Interaction interaction) {
        refreshed.add(interaction);
    }

    @Override
    public synchronized void acknowledge(String pressId, String text) {
        acknowledgements.add(text);
    }

    synchronized Interaction lastRefresh() {
        return refreshed.getLast();
    }
}
