package grocery.shoppinglist;

import java.util.Map;

/**
 * Unit conversion (spec §4.3.1). Mass and volume convert within their dimension; every other unit
 * (clove, can, bunch…) and "no unit" only add up with themselves. Across dimensions, a Food's
 * weight per unit ({@code gramsPer}) converts any unit to grams.
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

    /** The key {@code gramsPer} uses for an ingredient without a unit ("2 onions"). */
    static final String PIECE = "piece";

    /**
     * Grams in {@code quantity} of {@code unitName}, or null when the Food's weights don't cover the
     * unit. A weight for one volume unit covers every volume unit (it implies a density).
     */
    static Double grams(double quantity, String unitName, Map<String, Double> gramsPer) {
        Measure measure = measure(unitName);
        if (measure.dimension().equals("mass")) {
            return quantity * measure.factor();
        }
        Double perUnit = gramsPer.get(unitName == null ? PIECE : unitName);
        if (perUnit != null) {
            return quantity * perUnit;
        }
        if (measure.dimension().equals("volume")) {
            for (Map.Entry<String, Double> e : gramsPer.entrySet()) {
                Measure known = measure(e.getKey());
                if (known.dimension().equals("volume")) {
                    return quantity * measure.factor() / known.factor() * e.getValue();
                }
            }
        }
        return null;
    }

    static boolean isCountable(String unitName) {
        return unitName == null || !KNOWN.containsKey(unitName);
    }
}
