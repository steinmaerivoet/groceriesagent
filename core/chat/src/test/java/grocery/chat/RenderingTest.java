package grocery.chat;

import grocery.chat.Message.Checklist;
import grocery.chat.Message.Item;
import grocery.chat.Message.Summary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RenderingTest {

    @Test
    void callbackDataStaysWithinTelegramsLimit() {
        assertThat(Callback.toggle(Long.MAX_VALUE, 99).encode().getBytes()).hasSizeLessThanOrEqualTo(64);
        assertThat(Callback.decode(Callback.toggle(123456, 7).encode())).contains(new Callback(123456, "t7"));
        assertThat(Callback.decode("something-else")).isEmpty();
    }

    @Test
    void checklistShowsPreselectedItemsTicked() {
        var checklist = new Checklist("Stock check",
                List.of(new Item("p", "Passata ×2", true), new Item("r", "Rice", false)), "Confirm", null);
        var open = new Interaction(7, "run", "state", checklist, Set.of("p"), Interaction.Status.OPEN, "1", null, null, 0, Instant.EPOCH);

        var rendered = Rendering.render(checklist, open);

        assertThat(rendered.keyboard()).extracting(row -> row.getFirst().label())
                .containsExactly("☑ Passata ×2", "☐ Rice", "Confirm");
    }

    @Test
    void summariesAreAlignedMonospaceAndEscaped() {
        var rendered = Rendering.render(new Summary("Totals <AH>", List.of(
                List.of("Albert Heijn", "€68.10"),
                List.of("Colruyt", "€43.20"))), null);

        assertThat(rendered.html()).startsWith("<b>Totals &lt;AH&gt;</b>\n<pre>")
                .contains("Albert Heijn  €68.10\nColruyt       €43.20");
        assertThat(rendered.silent()).isTrue();
    }

    @Test
    void longMessagesAreCutToTelegramsLimit() {
        var rendered = Rendering.render(new Message.Notice("x".repeat(5000), false), null);

        assertThat(rendered.html()).hasSize(Rendering.MAX_LENGTH);
    }
}
