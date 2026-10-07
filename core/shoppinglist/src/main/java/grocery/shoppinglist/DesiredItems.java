package grocery.shoppinglist;

import grocery.contracts.NoteItem;
import grocery.contracts.RequiredItem;
import grocery.contracts.ShoppingRequirements;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Turns requirements plus the household's stock-check answers into the items the list should hold. */
public final class DesiredItems {

    private DesiredItems() {
    }

    /** @param buy food ids the household answered "yes, buy it" for in the stock check */
    public static List<DesiredItem> from(ShoppingRequirements requirements, Set<String> buy) {
        List<DesiredItem> items = new ArrayList<>();
        requirements.autoItems().forEach(i -> items.add(item(i, "meal-plan")));
        requirements.checkQuestions().stream().filter(i -> buy.contains(i.foodId()))
                .forEach(i -> items.add(item(i, "check-answer")));
        for (NoteItem note : requirements.noteItems()) {
            items.add(new DesiredItem("note|" + note.text(), null, null, null, null, null, note.text(), "meal-plan"));
        }
        return items;
    }

    private static DesiredItem item(RequiredItem i, String origin) {
        String key = i.foodId() + "|" + (i.unitId() == null ? "-" : i.unitId());
        String note = i.otherAmounts().isEmpty() ? "" : "+ " + String.join(" + ", i.otherAmounts());
        return new DesiredItem(key, i.foodId(), i.foodName(), i.labelId(), i.unitId(), i.quantity(), note, origin);
    }
}
