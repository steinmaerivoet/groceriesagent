package grocery.shoppinglist;

import grocery.shoppinglist.Reconciliation.Create;
import grocery.shoppinglist.Reconciliation.Delete;
import grocery.shoppinglist.Reconciliation.Update;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The five situations of spec §4.3.3, plus re-running without changes. */
class ReconcilerTest {

    private static DesiredItem want(String food, double quantity) {
        return new DesiredItem(food + "|-", food, food, null, null, quantity, "", "meal-plan");
    }

    private static ListItem live(String id, String food, double quantity, boolean checked, boolean managed) {
        return new ListItem(id, food, null, quantity, "", checked, managed);
    }

    private static WrittenItem wrote(String id, String food, double quantity) {
        return new WrittenItem(id, food + "|-", food, food, quantity, "", "2026-W42");
    }

    @Test
    void addsWhatIsMissing() {
        var result = Reconciler.reconcile(List.of(want("leek", 2)), List.of(), Map.of());

        assertThat(result.actions()).singleElement().isInstanceOf(Create.class);
    }

    @Test
    void rerunningWithoutChangesDoesNothing() {
        var result = Reconciler.reconcile(List.of(want("leek", 2)),
                List.of(live("1", "leek", 2, false, true)), Map.of("1", wrote("1", "leek", 2)));

        assertThat(result.actions()).isEmpty();
    }

    @Test
    void updatesAnUnchangedManagedItemWhoseQuantityChanged() {
        var result = Reconciler.reconcile(List.of(want("leek", 3)),
                List.of(live("1", "leek", 2, false, true)), Map.of("1", wrote("1", "leek", 2)));

        assertThat(result.actions()).singleElement().isInstanceOfSatisfying(Update.class,
                u -> assertThat(u.item().quantity()).isEqualTo(3.0));
    }

    @Test
    void removesAnUnchangedManagedItemThatIsNoLongerNeeded() {
        var result = Reconciler.reconcile(List.of(),
                List.of(live("1", "leek", 2, false, true)), Map.of("1", wrote("1", "leek", 2)));

        assertThat(result.actions()).singleElement().isInstanceOf(Delete.class);
    }

    @Test
    void leavesManagedItemsTheHouseholdChanged() {
        var editedQuantity = live("1", "leek", 5, false, true);
        var checkedOff = live("2", "carrot", 2, true, true);

        var result = Reconciler.reconcile(List.of(want("leek", 3)), List.of(editedQuantity, checkedOff),
                Map.of("1", wrote("1", "leek", 2), "2", wrote("2", "carrot", 2)));

        assertThat(result.actions()).isEmpty();
        assertThat(result.notes()).hasSize(2);
    }

    @Test
    void neverTouchesUserItemsAndDoesNotDuplicateThem() {
        var theirs = live("9", "leek", 1, false, false);

        var result = Reconciler.reconcile(List.of(want("leek", 2)), List.of(theirs), Map.of());

        assertThat(result.actions()).isEmpty();
        assertThat(result.notes()).singleElement().asString().contains("already on the list");
    }

    @Test
    void doesNotAddBackAnItemTheHouseholdRemoved() {
        var result = Reconciler.reconcile(List.of(want("leek", 2)), List.of(), Map.of("1", wrote("1", "leek", 2)));

        assertThat(result.actions()).isEmpty();
        assertThat(result.notes()).singleElement().asString().contains("You removed leek");
    }
}
