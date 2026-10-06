package grocery.shoppinglist;

import java.util.List;

/** The changes needed to bring the Mealie list in line with the requirements, plus what was left alone and why. */
public record Reconciliation(List<Action> actions, List<String> notes) {

    public sealed interface Action permits Create, Update, Delete {
    }

    public record Create(DesiredItem item) implements Action {
    }

    public record Update(String itemId, WrittenItem was, DesiredItem item) implements Action {
    }

    public record Delete(String itemId, WrittenItem was) implements Action {
    }
}
