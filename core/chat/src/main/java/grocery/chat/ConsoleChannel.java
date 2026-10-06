package grocery.chat;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Prints messages to the terminal instead of Telegram, numbering every callback button so a
 * press can be typed. Lets the chat flow be tried without a bot token.
 */
public final class ConsoleChannel implements ChatChannel {

    private final PrintStream out;
    private final AtomicInteger messageIds = new AtomicInteger();
    private final AtomicInteger buttonNumbers = new AtomicInteger();
    private final Map<Integer, String> buttons = new HashMap<>();
    private final Map<String, Integer> numbers = new HashMap<>();

    public ConsoleChannel(PrintStream out) {
        this.out = out;
    }

    /** The callback data behind a printed button number, or null. */
    public synchronized String button(int number) {
        return buttons.get(number);
    }

    @Override
    public synchronized String post(Message message, Interaction interaction) {
        String id = String.valueOf(messageIds.incrementAndGet());
        print("message " + id, Rendering.render(message, interaction));
        return id;
    }

    @Override
    public synchronized void refresh(Interaction interaction) {
        print("message " + interaction.messageId() + " (edited)", Rendering.render(interaction.message(), interaction));
    }

    @Override
    public synchronized void acknowledge(String pressId, String text) {
        if (!text.isBlank()) {
            out.println("  (pop-up: " + text + ")");
        }
    }

    private void print(String header, Rendering.Rendered r) {
        out.println();
        out.println("── " + header + (r.silent() ? ", silent" : "") + " " + "─".repeat(Math.max(0, 50 - header.length())));
        out.println(r.html().replaceAll("</?(b|i|pre)>", "").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&"));
        for (List<Rendering.Button> row : r.keyboard()) {
            StringBuilder line = new StringBuilder();
            for (Rendering.Button b : row) {
                if (b.url() != null) {
                    line.append("[").append(b.label()).append(" → ").append(b.url()).append("]  ");
                } else {
                    // The same button keeps its number when a message is edited.
                    int n = numbers.computeIfAbsent(b.callbackData(), d -> buttonNumbers.incrementAndGet());
                    buttons.put(n, b.callbackData());
                    line.append("[").append(n).append(": ").append(b.label()).append("]  ");
                }
            }
            out.println(line.toString().stripTrailing());
        }
        if (r.forceReply()) {
            out.println("(reply expected)");
        }
    }
}
