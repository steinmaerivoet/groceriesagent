package grocery.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/** Mealie's meal-plan entry types (spec §3.4.1). */
public enum MealType {
    BREAKFAST, LUNCH, DINNER, SIDE, SNACK, DRINK, DESSERT;

    /** The lower-case value Mealie uses in its API. */
    @JsonValue
    public String mealieValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static MealType fromMealie(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
