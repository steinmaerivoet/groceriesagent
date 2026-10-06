package grocery.contracts;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JsonTest {

    @Test
    void contractsRoundTripThroughJson() throws Exception {
        var slot = new Slot(LocalDate.of(2026, 10, 12), MealType.DINNER);
        var plan = new ProposedPlan("2026-W42",
                List.of(new PlannedSlot(slot, "r1", false, 1.5, List.of(new ScoreTerm("rating", 1.0, "rated 5")))),
                1.5, List.of(), List.of());

        String json = Json.MAPPER.writeValueAsString(plan);

        assertThat(json).contains("\"mealType\" : \"dinner\"").contains("\"date\" : \"2026-10-12\"");
        assertThat(Json.MAPPER.readValue(json, ProposedPlan.class)).isEqualTo(plan);
    }
}
