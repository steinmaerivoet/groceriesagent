package grocery.contracts;

import java.time.LocalDate;
import java.util.Comparator;

/** One date × meal type to be filled with a recipe, e.g. Tuesday dinner. */
public record Slot(LocalDate date, MealType mealType) implements Comparable<Slot> {

    private static final Comparator<Slot> ORDER = Comparator.comparing(Slot::date).thenComparing(Slot::mealType);

    @Override
    public int compareTo(Slot other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return date.getDayOfWeek().toString().substring(0, 3) + " " + date + " " + mealType.mealieValue();
    }
}
