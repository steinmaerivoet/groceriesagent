package grocery.chat;

import grocery.chat.Interaction.Status;
import grocery.chat.Message.Checklist;
import grocery.chat.Message.Choice;
import grocery.chat.Message.FreeText;
import grocery.chat.Message.Link;
import grocery.chat.Message.Notice;
import grocery.chat.Message.Summary;

import java.util.ArrayList;
import java.util.List;

/**
 * How primitives look in Telegram (spec §5.3), kept free of Telegram library types so it can be
 * tested on its own. Text is Telegram HTML.
 */
public final class Rendering {

    /** Telegram's message length limit. */
    public static final int MAX_LENGTH = 4096;

    /** A button opens a URL or sends callback data back to the bot. */
    public record Button(String label, String callbackData, String url) {

        static Button callback(String label, Callback callback) {
            return new Button(label, callback.encode(), null);
        }

        static Button link(String label, String url) {
            return new Button(label, null, url);
        }
    }

    /** A rendered message: HTML text, keyboard rows and how to deliver it. */
    public record Rendered(String html, List<List<Button>> keyboard, boolean silent, boolean forceReply) {
    }

    private Rendering() {
    }

    /** @param interaction the interaction behind a question, or null for plain information */
    public static Rendered render(Message message, Interaction interaction) {
        return switch (message) {
            case Notice n -> new Rendered(limit(escape(n.text())), List.of(), n.silent(), false);
            case Summary s -> new Rendered(limit("<b>" + escape(s.title()) + "</b>\n<pre>" + escape(table(s.rows())) + "</pre>"),
                    List.of(), true, false);
            case Link l -> new Rendered(limit(escape(l.text())), List.of(List.of(Button.link(l.label(), l.url()))), true, false);
            case FreeText f -> new Rendered(limit(escape(f.question())), List.of(), false, true);
            case Choice c -> choice(c, interaction);
            case Checklist c -> checklist(c, interaction);
        };
    }

    private static Rendered choice(Choice choice, Interaction i) {
        List<List<Button>> keyboard = new ArrayList<>();
        StringBuilder html = new StringBuilder(escape(choice.text()));
        if (i.status() == Status.OPEN) {
            List<Button> row = new ArrayList<>();
            for (int n = 0; n < choice.options().size(); n++) {
                row.add(Button.callback(choice.options().get(n).label(), Callback.option(i.id(), n)));
            }
            keyboard.add(row);
        } else {
            html.append("\n\n").append(closing(i));
        }
        addLink(keyboard, choice.link());
        return new Rendered(limit(html.toString()), keyboard, false, false);
    }

    private static Rendered checklist(Checklist checklist, Interaction i) {
        List<List<Button>> keyboard = new ArrayList<>();
        StringBuilder html = new StringBuilder("<b>" + escape(checklist.text()) + "</b>");
        if (i.status() == Status.OPEN) {
            for (int n = 0; n < checklist.items().size(); n++) {
                Message.Item item = checklist.items().get(n);
                keyboard.add(List.of(Button.callback(tick(i, item) + " " + item.label(), Callback.toggle(i.id(), n))));
            }
            keyboard.add(List.of(Button.callback(checklist.confirmLabel(), Callback.confirm(i.id()))));
        } else {
            for (Message.Item item : checklist.items()) {
                html.append("\n").append(tick(i, item)).append(" ").append(escape(item.label()));
            }
            html.append("\n\n").append(closing(i));
        }
        addLink(keyboard, checklist.link());
        return new Rendered(limit(html.toString()), keyboard, false, false);
    }

    private static String tick(Interaction i, Message.Item item) {
        return i.selected().contains(item.id()) ? "☑" : "☐";
    }

    private static String closing(Interaction i) {
        if (i.status() != Status.ANSWERED) {
            return "<i>Outdated: this question no longer applies.</i>";
        }
        return i.message() instanceof Choice
                ? "✓ <i>" + escape(i.answeredBy()) + " chose “" + escape(i.outcome()) + "”</i>"
                : "✓ <i>" + escape(i.outcome()) + " by " + escape(i.answeredBy()) + "</i>";
    }

    private static void addLink(List<List<Button>> keyboard, Link link) {
        if (link != null) {
            keyboard.add(List.of(Button.link(link.label(), link.url())));
        }
    }

    /** Aligns columns; Telegram has no tables (spec §5.3). */
    static String table(List<List<String>> rows) {
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        int[] widths = new int[columns];
        rows.forEach(r -> {
            for (int c = 0; c < r.size(); c++) {
                widths[c] = Math.max(widths[c], r.get(c).length());
            }
        });
        StringBuilder out = new StringBuilder();
        for (List<String> row : rows) {
            for (int c = 0; c < row.size(); c++) {
                String cell = row.get(c);
                boolean last = c == row.size() - 1;
                boolean alignRight = c > 0 && looksNumeric(cell);
                out.append(pad(cell, widths[c], !alignRight));
                if (!last) {
                    out.append("  ");
                }
            }
            out.append('\n');
        }
        return out.toString().stripTrailing();
    }

    private static boolean looksNumeric(String s) {
        return s.matches("[€$]?[-0-9.,]+%?");
    }

    private static String pad(String s, int width, boolean left) {
        String fill = " ".repeat(width - s.length());
        return left ? s + fill : fill + s;
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Longer content belongs behind a Mealie link (spec §5.3); cut as a last resort. */
    static String limit(String html) {
        return html.length() <= MAX_LENGTH ? html : html.substring(0, MAX_LENGTH - 1) + "…";
    }
}
