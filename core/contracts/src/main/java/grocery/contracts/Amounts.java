package grocery.contracts;

/** Formats a quantity with its unit for people: "75 gram", "1.5 tablespoon", "2". */
public final class Amounts {

    private Amounts() {
    }

    public static String format(double quantity, String unitName) {
        String number = quantity == Math.rint(quantity) ? String.valueOf((long) quantity) : String.valueOf(quantity);
        return unitName == null ? number : number + " " + unitName;
    }
}
