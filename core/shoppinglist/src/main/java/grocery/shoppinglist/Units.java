package grocery.shoppinglist;

import java.util.Map;

/**
 * Unit conversion within a dimension (spec §4.3.1). Mass and volume convert; every other unit
 * (clove, can, bunch…) and "no unit" only add up with themselves.
 */
final class Units {

    /** A unit's dimension and its size in the dimension's base unit (g or ml). */
    record Measure(String dimension, double factor) {
    }

    private static final Map<String, Measure> KNOWN = Map.of(
            "gram", new Measure("mass", 1),
            "kilogram", new Measure("mass", 1000),
            "millilitre", new Measure("volume", 1),
            "litre", new Measure("volume", 1000),
            "teaspoon", new Measure("volume", 5),
            "tablespoon", new Measure("volume", 15));

    private Units() {
    }

    static Measure measure(String unitName) {
        if (unitName == null) {
            return new Measure("count", 1);
        }
        return KNOWN.getOrDefault(unitName, new Measure("unit:" + unitName, 1));
    }

    static boolean isCountable(String unitName) {
        return unitName == null || !KNOWN.containsKey(unitName);
    }
}
