package com.dqs.api.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared coercions for untyped rows and request bodies.
 *
 * Everything that reads a legacy `ps_*` table or a `Map<String, Object>` body
 * comes through here, and the driver decides whether a column arrives as a
 * Long, an Integer, a BigDecimal or text. None of these throw, which is the
 * point — and also the risk: a value that quietly becomes 0 is a figure nobody
 * questions. So each one is exercised with every shape it can meet.
 *
 * The pair that matters most is the split between `toInt` and `toIntOrNull`.
 * Zero and absent are the same thing to a quantity and very different things to
 * a nullable column, which is why both exist.
 */
class MapUtilsTest {

    @Test
    @DisplayName("numbers coerce from every shape the driver hands back")
    void numbersCoerceFromEveryShape() {
        for (Object v : new Object[] { 42, 42L, 42.0, new BigDecimal("42"), (short) 42, "42" }) {
            assertThat(MapUtils.toInt(v)).as("toInt(%s)", v).isEqualTo(42);
            assertThat(MapUtils.toLong(v)).as("toLong(%s)", v).isEqualTo(42L);
            assertThat(MapUtils.toDouble(v)).as("toDouble(%s)", v).isEqualTo(42.0);
            assertThat(MapUtils.toDecimal(v)).as("toDecimal(%s)", v).isEqualByComparingTo("42");
        }
    }

    @Test
    @DisplayName("a decimal narrows to an int by truncation, not by rounding")
    void decimalsTruncateWhenNarrowed() {
        // Worth knowing: 2.7 pallets reads as 2, not 3.
        assertThat(MapUtils.toInt(2.7)).isEqualTo(2);
        assertThat(MapUtils.toLong(new BigDecimal("2.7"))).isEqualTo(2L);
    }

    @Test
    @DisplayName("nothing and nonsense both become zero rather than throwing")
    void absentAndUnparseableBecomeZero() {
        for (Object v : new Object[] { null, "", "N/A", "dos" }) {
            assertThat(MapUtils.toInt(v)).as("toInt(%s)", v).isZero();
            assertThat(MapUtils.toLong(v)).as("toLong(%s)", v).isZero();
            assertThat(MapUtils.toDouble(v)).as("toDouble(%s)", v).isZero();
            assertThat(MapUtils.toDecimal(v)).as("toDecimal(%s)", v).isEqualByComparingTo("0");
        }
    }

    @Test
    @DisplayName("the OrNull variants keep absent distinct from zero")
    void theOrNullVariantsKeepAbsentDistinct() {
        // A nullable column must be able to say "no value". Zero would read as
        // a real figure — a route with no minimum is not a route whose minimum
        // is none required.
        assertThat(MapUtils.toIntOrNull(null)).isNull();
        assertThat(MapUtils.toLongOrNull(null)).isNull();
        assertThat(MapUtils.strOrNull(null)).isNull();

        assertThat(MapUtils.toIntOrNull(0)).isZero();
        assertThat(MapUtils.toLongOrNull(0L)).isZero();
    }

    @Test
    @DisplayName("the OrNull variants coerce the same shapes, and answer null on nonsense")
    void theOrNullVariantsCoerceTheSameShapes() {
        assertThat(MapUtils.toIntOrNull(new BigDecimal("42"))).isEqualTo(42);
        assertThat(MapUtils.toIntOrNull("42")).isEqualTo(42);
        assertThat(MapUtils.toIntOrNull("cuarenta y dos")).isNull();

        assertThat(MapUtils.toLongOrNull(42)).isEqualTo(42L);
        assertThat(MapUtils.toLongOrNull("42")).isEqualTo(42L);
        assertThat(MapUtils.toLongOrNull("N/A")).isNull();
    }

    @Test
    @DisplayName("text is never null from str, and never the word \"null\" from strOrNull")
    void textCoercions() {
        assertThat(MapUtils.str(null)).isEmpty();
        assertThat(MapUtils.str("")).isEmpty();
        assertThat(MapUtils.str(42)).isEqualTo("42");
        assertThat(MapUtils.str(new BigDecimal("42.50"))).isEqualTo("42.50");

        assertThat(MapUtils.strOrNull(42)).isEqualTo("42");
        // The distinction the delivery-log rows need: a null date must stay
        // null rather than reaching the client as the string "null".
        assertThat(MapUtils.strOrNull(null)).isNull();
    }

    @Test
    @DisplayName("a flag reads every shape a caller sends")
    void flagsReadEveryShape() {
        assertThat(MapUtils.toBool(true)).isTrue();
        assertThat(MapUtils.toBool(Boolean.FALSE)).isFalse();
        assertThat(MapUtils.toBool(1)).isTrue();
        assertThat(MapUtils.toBool(0)).isFalse();
        assertThat(MapUtils.toBool(-1)).isTrue();
        assertThat(MapUtils.toBool(new BigDecimal("1"))).isTrue();
        assertThat(MapUtils.toBool("1")).isTrue();
        assertThat(MapUtils.toBool("true")).isTrue();
        assertThat(MapUtils.toBool("TRUE")).isTrue();
        assertThat(MapUtils.toBool("  true  ")).isTrue();
        assertThat(MapUtils.toBool("0")).isFalse();
        assertThat(MapUtils.toBool("false")).isFalse();
        assertThat(MapUtils.toBool("sí")).isFalse();
        assertThat(MapUtils.toBool(null)).isFalse();
    }

    @Test
    @DisplayName("a nested map comes back usable, and anything else comes back empty")
    void castMapIsAlwaysUsable() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("pais_iso2", "CO");

        assertThat(MapUtils.castMap(nested)).isSameAs(nested);
        // Never null: every caller reads straight off the result.
        assertThat(MapUtils.castMap(null)).isEmpty();
        assertThat(MapUtils.castMap("no soy un mapa")).isEmpty();
        assertThat(MapUtils.castMap(List.of(1, 2))).isEmpty();
    }

    @Test
    @DisplayName("coalesce takes the first value that exists")
    void coalesceTakesTheFirstPresent() {
        assertThat(MapUtils.coalesce("CO", "CR")).isEqualTo("CO");
        assertThat(MapUtils.coalesce(null, "CR")).isEqualTo("CR");
        assertThat(MapUtils.<String>coalesce(null, null)).isNull();
        // Zero and empty are values, not absences.
        assertThat(MapUtils.coalesce(BigDecimal.ZERO, BigDecimal.ONE)).isEqualByComparingTo("0");
        assertThat(MapUtils.coalesce("", "CR")).isEmpty();
    }
}
