package grocery.contracts;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.stream.Stream;

/**
 * An ISO week to plan, e.g. {@code 2026-W42}. Its id doubles as the planning-run id and the
 * planner's random seed (spec §3.5).
 */
public record PlanningWeek(int year, int week) {

    public static PlanningWeek parse(String id) {
        String[] parts = id.split("-W");
        return new PlanningWeek(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }

    public static PlanningWeek containing(LocalDate date) {
        return new PlanningWeek(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    /** The week after the one containing {@code today}: the default planning week. */
    public static PlanningWeek after(LocalDate today) {
        return containing(today.plusWeeks(1));
    }

    public String id() {
        return "%d-W%02d".formatted(year, week);
    }

    public LocalDate start() {
        return LocalDate.of(year, 6, 1)
                .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public LocalDate end() {
        return start().plusDays(6);
    }

    /** One slot per day for each meal type (spec §7.1 default: dinner every day). */
    public List<Slot> slots(List<MealType> mealTypes) {
        return Stream.iterate(start(), d -> !d.isAfter(end()), d -> d.plusDays(1))
                .flatMap(d -> mealTypes.stream().map(t -> new Slot(d, t)))
                .toList();
    }

    @Override
    public String toString() {
        return id();
    }
}
