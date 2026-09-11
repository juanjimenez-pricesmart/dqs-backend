package com.dqs.api.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which country a club belongs to, and what that country's tax rules are.
 *
 * Every range is tested at both ends, and that is the whole point: the bounds
 * here are INCLUSIVE, which is a deliberate divergence from legacy DQS. Legacy
 * tests Colombia as `6100 < storeId && storeId < 6199`, so clubs 6100 and 6199
 * fall through to the generic branch there. Ours does not, and the tests are
 * what stop someone "fixing" it back — a club at the edge of a range would
 * silently get another country's tax arithmetic and another country's totals
 * block.
 */
class ClubCapabilitiesTest {

    private ClubCapabilities capabilities(boolean fel, boolean paymentLink,
                                          boolean delivery, boolean pilot) {
        ClubCapabilities c = new ClubCapabilities();
        set(c, "felEnabled", fel);
        set(c, "paymentLinkEnabled", paymentLink);
        set(c, "deliveryEnabled", delivery);
        set(c, "pilotEnabled", pilot);
        return c;
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = ClubCapabilities.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot set " + field, e);
        }
    }

    @Test
    @DisplayName("every range includes both of its ends, unlike legacy's comparison")
    void rangesIncludeBothEnds() {
        // The lower bound, the upper bound, and one outside each.
        assertThat(ClubCapabilities.isColombia(6100)).isTrue();
        assertThat(ClubCapabilities.isColombia(6199)).isTrue();
        assertThat(ClubCapabilities.isColombia(6099)).isFalse();
        assertThat(ClubCapabilities.isColombia(6200)).isFalse();

        assertThat(ClubCapabilities.isPanama(6200)).isTrue();
        assertThat(ClubCapabilities.isPanama(6299)).isTrue();
        assertThat(ClubCapabilities.isPanama(6300)).isFalse();

        assertThat(ClubCapabilities.isGuatemala(6300)).isTrue();
        assertThat(ClubCapabilities.isGuatemala(6399)).isTrue();
        assertThat(ClubCapabilities.isGuatemala(6400)).isFalse();

        assertThat(ClubCapabilities.isCostaRica(6400)).isTrue();
        assertThat(ClubCapabilities.isCostaRica(6499)).isTrue();
        assertThat(ClubCapabilities.isCostaRica(6500)).isFalse();

        assertThat(ClubCapabilities.isElSalvador(6500)).isTrue();
        assertThat(ClubCapabilities.isElSalvador(6599)).isTrue();
        assertThat(ClubCapabilities.isElSalvador(6600)).isFalse();

        assertThat(ClubCapabilities.isEcuador(6800)).isTrue();
        assertThat(ClubCapabilities.isEcuador(6899)).isTrue();

        assertThat(ClubCapabilities.isBarbados(8500)).isTrue();
        assertThat(ClubCapabilities.isBarbados(8599)).isTrue();
        assertThat(ClubCapabilities.isBarbados(8600)).isFalse();

        assertThat(ClubCapabilities.isNicaragua(8900)).isTrue();
        assertThat(ClubCapabilities.isNicaragua(8999)).isTrue();

        assertThat(ClubCapabilities.isPeru(9000)).isTrue();
        assertThat(ClubCapabilities.isPeru(9099)).isTrue();
        assertThat(ClubCapabilities.isPeru(9100)).isFalse();
    }

    @Test
    @DisplayName("Honduras is four specific clubs, not a range")
    void hondurasIsAListOfClubs() {
        // Its ids sit inside El Salvador's range, which is why a range would
        // have put Honduran clubs in the wrong country.
        assertThat(ClubCapabilities.isHonduras(6601)).isTrue();
        assertThat(ClubCapabilities.isHonduras(6604)).isTrue();
        assertThat(ClubCapabilities.isHonduras(6605)).isFalse();
        assertThat(ClubCapabilities.isHonduras(6600)).isFalse();
    }

    @Test
    @DisplayName("a club in no known range belongs to no country")
    void anUnknownClubBelongsNowhere() {
        int orphan = 7500;

        assertThat(ClubCapabilities.isColombia(orphan)).isFalse();
        assertThat(ClubCapabilities.isPanama(orphan)).isFalse();
        assertThat(ClubCapabilities.isGuatemala(orphan)).isFalse();
        assertThat(ClubCapabilities.isCostaRica(orphan)).isFalse();
        assertThat(ClubCapabilities.isElSalvador(orphan)).isFalse();
        assertThat(ClubCapabilities.isHonduras(orphan)).isFalse();
        assertThat(ClubCapabilities.isEcuador(orphan)).isFalse();
        assertThat(ClubCapabilities.isBarbados(orphan)).isFalse();
        assertThat(ClubCapabilities.isNicaragua(orphan)).isFalse();
        assertThat(ClubCapabilities.isPeru(orphan)).isFalse();
    }

    @Test
    @DisplayName("the price includes the tax in Guatemala and Panama, and nowhere else")
    void vatInclusiveIsTwoCountries() {
        assertThat(ClubCapabilities.isVatInclusive(6301)).isTrue();   // Guatemala
        assertThat(ClubCapabilities.isVatInclusive(6201)).isTrue();   // Panama
        assertThat(ClubCapabilities.isVatInclusive(6101)).isFalse();  // Colombia
        assertThat(ClubCapabilities.isVatInclusive(6401)).isFalse();  // Costa Rica
        assertThat(ClubCapabilities.isVatInclusive(8501)).isFalse();  // Barbados
    }

    @Test
    @DisplayName("Panama, Nicaragua and Peru charge no tax at all")
    void threeCountriesChargeNoTax() {
        assertThat(ClubCapabilities.usesNoIva(6201)).isTrue();   // Panama
        assertThat(ClubCapabilities.usesNoIva(8901)).isTrue();   // Nicaragua
        assertThat(ClubCapabilities.usesNoIva(9001)).isTrue();   // Peru
        assertThat(ClubCapabilities.usesNoIva(6101)).isFalse();
        assertThat(ClubCapabilities.usesNoIva(6401)).isFalse();
    }

    @Test
    @DisplayName("Panama is both: its price includes a tax it does not charge")
    void panamaIsBoth() {
        // Which reads as a contradiction and is not: the rate column is gross
        // and the tax factor is zero, so gross and net coincide.
        assertThat(ClubCapabilities.isVatInclusive(6201)).isTrue();
        assertThat(ClubCapabilities.usesNoIva(6201)).isTrue();
    }

    @Test
    @DisplayName("electronic invoicing is Costa Rica and El Salvador")
    void felIsTwoCountries() {
        assertThat(ClubCapabilities.isFelStore(6401)).isTrue();
        assertThat(ClubCapabilities.isFelStore(6501)).isTrue();
        assertThat(ClubCapabilities.isFelStore(6301)).isFalse();
        assertThat(ClubCapabilities.isFelStore(6101)).isFalse();
    }

    @Test
    @DisplayName("the fiscal step needs the country AND the feature switch")
    void theFiscalStepNeedsBoth() {
        // A country that supports it, with the switch off.
        assertThat(capabilities(false, false, true, false).forStore(6401).isRequiresFiscalStep()).isFalse();
        // The switch on, in a country that does not.
        assertThat(capabilities(true, false, true, false).forStore(6101).isRequiresFiscalStep()).isFalse();
        // Both.
        assertThat(capabilities(true, false, true, false).forStore(6401).isRequiresFiscalStep()).isTrue();
    }

    @Test
    @DisplayName("the other three switches are global, not per country")
    void theOtherSwitchesAreGlobal() {
        ClubCapabilities.Capabilities all = capabilities(false, true, true, true).forStore(6101);
        assertThat(all.isSupportsPaymentLink()).isTrue();
        assertThat(all.isSupportsDelivery()).isTrue();
        assertThat(all.isPilotEnabled()).isTrue();

        ClubCapabilities.Capabilities none = capabilities(false, false, false, false).forStore(6101);
        assertThat(none.isSupportsPaymentLink()).isFalse();
        assertThat(none.isSupportsDelivery()).isFalse();
        assertThat(none.isPilotEnabled()).isFalse();
    }

    @Test
    @DisplayName("one club's capabilities name its country and only its country")
    void capabilitiesNameOneCountry() {
        ClubCapabilities.Capabilities colombia = capabilities(true, true, true, true).forStore(6101);

        assertThat(colombia.isColombia()).isTrue();
        assertThat(colombia.isPanama()).isFalse();
        assertThat(colombia.isGuatemala()).isFalse();
        assertThat(colombia.isCostaRica()).isFalse();
        assertThat(colombia.isElSalvador()).isFalse();
        assertThat(colombia.isHonduras()).isFalse();
        assertThat(colombia.isEcuador()).isFalse();
        assertThat(colombia.isBarbados()).isFalse();
        assertThat(colombia.isNicaragua()).isFalse();
        assertThat(colombia.isPeru()).isFalse();
        assertThat(colombia.isVatInclusive()).isFalse();
        assertThat(colombia.isUsesNoIva()).isFalse();
    }

    @Test
    @DisplayName("a Honduran club reports Honduras and not El Salvador, whose range it sits in")
    void aHonduranClubIsNotSalvadoran() {
        ClubCapabilities.Capabilities honduras = capabilities(true, false, true, false).forStore(6601);

        assertThat(honduras.isHonduras()).isTrue();
        assertThat(honduras.isElSalvador()).isFalse();
        // And so it does not get El Salvador's fiscal step either.
        assertThat(honduras.isRequiresFiscalStep()).isFalse();
    }
}
