package com.dqs.api.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two SKUs that are not ordinary lines.
 *
 * The point of the registry is that a caller asks what an item DOES rather than
 * comparing its code, so a third special item is one entry rather than a hunt
 * through the codebase. These tests are the contract of that.
 */
class SpecialItemsTest {

    @Test
    @DisplayName("delivery and the gift card are the two special codes")
    void twoCodesAreSpecial() {
        assertThat(SpecialItems.isSpecial(SpecialItems.DELIVERY)).isTrue();
        assertThat(SpecialItems.isSpecial(SpecialItems.GIFT_CARD)).isTrue();
        assertThat(SpecialItems.isSpecial("1001")).isFalse();
        assertThat(SpecialItems.isSpecial(null)).isFalse();
    }

    @Test
    @DisplayName("the delivery SKU is owned by its panel and kept out of the line totals")
    void deliveryIsPanelOwnedAndUntotalled() {
        // Both traits together are why a pasted 888905 is refused and why the
        // items table does not list it: the Envio form owns that row.
        assertThat(SpecialItems.has(SpecialItems.DELIVERY, SpecialItems.Trait.OWNED_BY_PANEL)).isTrue();
        assertThat(SpecialItems.has(SpecialItems.DELIVERY, SpecialItems.Trait.EXCLUDED_FROM_LINE_TOTALS)).isTrue();
        assertThat(SpecialItems.has(SpecialItems.DELIVERY, SpecialItems.Trait.PRESET_AMOUNT)).isFalse();
    }

    @Test
    @DisplayName("the gift card carries a preset amount and nothing else")
    void theGiftCardOnlyHasAPresetAmount() {
        assertThat(SpecialItems.has(SpecialItems.GIFT_CARD, SpecialItems.Trait.PRESET_AMOUNT)).isTrue();
        // It IS a line on the quote, unlike the delivery SKU.
        assertThat(SpecialItems.has(SpecialItems.GIFT_CARD, SpecialItems.Trait.EXCLUDED_FROM_LINE_TOTALS)).isFalse();
        assertThat(SpecialItems.isRegularLine(SpecialItems.GIFT_CARD)).isTrue();
    }

    @Test
    @DisplayName("an ordinary code has no traits, and neither does a null one")
    void ordinaryAndNullCodesHaveNoTraits() {
        for (SpecialItems.Trait trait : SpecialItems.Trait.values()) {
            assertThat(SpecialItems.has("1001", trait)).isFalse();
            assertThat(SpecialItems.has(null, trait)).isFalse();
        }
    }

    @Test
    @DisplayName("a regular line is anything not excluded from the totals")
    void aRegularLineIsAnythingNotExcluded() {
        assertThat(SpecialItems.isRegularLine("1001")).isTrue();
        assertThat(SpecialItems.isRegularLine(SpecialItems.DELIVERY)).isFalse();
        // A null code counts as regular: the filter must not drop rows whose
        // product id failed to load, or they vanish from the quote silently.
        assertThat(SpecialItems.isRegularLine(null)).isTrue();
    }

    @Test
    @DisplayName("the two codes are recognisable by name as well as by trait")
    void theTwoCodesHaveNamedChecks() {
        assertThat(SpecialItems.isDelivery("888905")).isTrue();
        assertThat(SpecialItems.isDelivery("999979")).isFalse();
        assertThat(SpecialItems.isDelivery(null)).isFalse();

        assertThat(SpecialItems.isGiftCard("999979")).isTrue();
        assertThat(SpecialItems.isGiftCard("888905")).isFalse();
        assertThat(SpecialItems.isGiftCard(null)).isFalse();
    }
}
