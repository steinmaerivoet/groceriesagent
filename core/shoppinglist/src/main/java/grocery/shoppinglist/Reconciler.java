package grocery.shoppinglist;

import grocery.shoppinglist.Reconciliation.Action;
import grocery.shoppinglist.Reconciliation.Create;
import grocery.shoppinglist.Reconciliation.Delete;
import grocery.shoppinglist.Reconciliation.Update;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Spec §4.3.3: decides how re-generating the list changes Mealie, so that re-running never
 * duplicates managed items and never touches what the household created or changed (INV-05).
 * Pure: the caller reads the list and applies the result.
 */
public final class Reconciler {

    private Reconciler() {
    }

    /**
     * @param desired what the requirements want on the list now
     * @param current the live Mealie list
     * @param written what Grocery Core last wrote, per item id
     */
    public static Reconciliation reconcile(List<DesiredItem> desired, List<ListItem> current, Map<String, WrittenItem> written) {
        Map<String, DesiredItem> wanted = desired.stream()
                .collect(Collectors.toMap(DesiredItem::key, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<String, ListItem> live = current.stream().collect(Collectors.toMap(ListItem::id, Function.identity()));
        Set<String> handled = new HashSet<>();
        List<Action> actions = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        for (WrittenItem was : written.values()) {
            ListItem item = live.get(was.itemId());
            DesiredItem want = wanted.get(was.key());
            if (item == null) {
                // The household removed an item we added: their change wins, so don't add it back.
                if (want != null) {
                    handled.add(was.key());
                    notes.add("You removed " + was.label() + " from the list; not adding it again");
                }
                continue;
            }
            if (changedByUser(item, was)) {
                if (want != null) {
                    handled.add(was.key());
                }
                notes.add("Kept your version of " + was.label());
                continue;
            }
            if (want == null) {
                actions.add(new Delete(was.itemId(), was));
            } else {
                handled.add(was.key());
                if (!sameQuantity(want.quantity(), item.quantity()) || !Objects.equals(nullToEmpty(want.note()), nullToEmpty(item.note()))) {
                    actions.add(new Update(was.itemId(), was, want));
                }
            }
        }

        Set<String> ours = written.keySet();
        List<ListItem> theirs = current.stream().filter(i -> !ours.contains(i.id())).toList();
        for (DesiredItem want : wanted.values()) {
            if (handled.contains(want.key())) {
                continue;
            }
            boolean alreadyThere = theirs.stream().anyMatch(i -> want.foodId() != null
                    ? want.foodId().equals(i.foodId())
                    : i.foodId() == null && Objects.equals(want.note(), i.note()));
            if (alreadyThere) {
                notes.add(want.label() + " is already on the list; not adding it twice");
            } else {
                actions.add(new Create(want));
            }
        }
        return new Reconciliation(actions, notes);
    }

    private static boolean changedByUser(ListItem item, WrittenItem was) {
        return item.checked()
                || !sameQuantity(item.quantity(), was.quantity())
                || !Objects.equals(nullToEmpty(item.note()), nullToEmpty(was.note()));
    }

    /** Mealie stores "no quantity" as 0. */
    private static boolean sameQuantity(Double a, Double b) {
        return Math.abs((a == null ? 0 : a) - (b == null ? 0 : b)) < 1e-6;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
