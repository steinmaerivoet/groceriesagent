package grocery.contracts;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanningWeekTest {

    @Test
    void parsesIsoWeekIds() {
        var week = PlanningWeek.parse("2026-W42");
        assertThat(week.start()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(week.end()).isEqualTo(LocalDate.of(2026, 10, 18));
        assertThat(week.id()).isEqualTo("2026-W42");
    }

    @Test
    void handlesWeekOneThatStartsInThePreviousYear() {
        assertThat(PlanningWeek.parse("2026-W01").start()).isEqualTo(LocalDate.of(2025, 12, 29));
        assertThat(PlanningWeek.containing(LocalDate.of(2025, 12, 30)).id()).isEqualTo("2026-W01");
    }

    @Test
    void defaultsToNextWeek() {
        assertThat(PlanningWeek.after(LocalDate.of(2026, 10, 10)).id()).isEqualTo("2026-W42");
    }

    @Test
    void buildsOneSlotPerDayAndMealType() {
        var slots = PlanningWeek.parse("2026-W42").slots(List.of(MealType.DINNER));
        assertThat(slots).hasSize(7);
        assertThat(slots.getFirst()).isEqualTo(new Slot(LocalDate.of(2026, 10, 12), MealType.DINNER));
    }
}
