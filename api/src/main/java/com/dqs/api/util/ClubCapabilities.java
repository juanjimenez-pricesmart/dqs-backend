package com.dqs.api.util;

import lombok.Builder;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for per-club feature capabilities.
 * Add new country ranges or feature toggles here — never scatter
 * country checks (isColombia, isCostaRica, etc.) across services.
 */
@Component
public class ClubCapabilities {

    // ── Country/region store ID ranges ────────────────────────────────────────
    private static final int COLOMBIA_MIN    = 6100; private static final int COLOMBIA_MAX    = 6199;
    private static final int PANAMA_MIN      = 6200; private static final int PANAMA_MAX      = 6299;
    private static final int GUATEMALA_MIN   = 6300; private static final int GUATEMALA_MAX   = 6399;
    private static final int COSTA_RICA_MIN  = 6400; private static final int COSTA_RICA_MAX  = 6499;
    private static final int EL_SALVADOR_MIN = 6500; private static final int EL_SALVADOR_MAX = 6599;
    private static final int ECUADOR_MIN     = 6800; private static final int ECUADOR_MAX     = 6899;
    private static final int TRINIDAD_MIN    = 8000; private static final int TRINIDAD_MAX    = 8099;
    private static final int USVI_MIN        = 8100; private static final int USVI_MAX        = 8199;
    private static final int ARUBA_MIN       = 8200; private static final int ARUBA_MAX       = 8299;
    private static final int BARBADOS_MIN    = 8500; private static final int BARBADOS_MAX    = 8599;
    private static final int JAMAICA_MIN     = 8700; private static final int JAMAICA_MAX     = 8799;
    private static final int NICARAGUA_MIN   = 8900; private static final int NICARAGUA_MAX   = 8999;
    private static final int PERU_MIN        = 9000; private static final int PERU_MAX        = 9099;

    // Honduras uses specific IDs (avoids range overlap with El Salvador)
    private static final java.util.Set<Integer> HONDURAS_IDS =
        java.util.Set.of(6601, 6602, 6603, 6604);

    // ── Feature flags (wired from application.properties) ─────────────────────
    @Value("${quotecenter.payment-link.enabled:false}")
    private boolean paymentLinkEnabled;

    @Value("${quotecenter.fel.enabled:false}")
    private boolean felEnabled;

    @Value("${quotecenter.delivery.enabled:true}")
    private boolean deliveryEnabled;

    @Value("${quotecenter.pilot.enabled:false}")
    private boolean pilotEnabled;

    // ── Capability resolution ─────────────────────────────────────────────────

    public Capabilities forStore(int storeId) {
        return Capabilities.builder()
            .isColombia(isColombia(storeId))
            .isPanama(isPanama(storeId))
            .isGuatemala(isGuatemala(storeId))
            .isCostaRica(isCostaRica(storeId))
            .isElSalvador(isElSalvador(storeId))
            .isHonduras(isHonduras(storeId))
            .isEcuador(isEcuador(storeId))
            .isBarbados(isBarbados(storeId))
            .isNicaragua(isNicaragua(storeId))
            .isPeru(isPeru(storeId))
            .isVatInclusive(isVatInclusive(storeId))
            .usesNoIva(usesNoIva(storeId))
            .requiresFiscalStep(felEnabled && isFelStore(storeId))
            .supportsPaymentLink(paymentLinkEnabled)
            .supportsDelivery(deliveryEnabled)
            .isPilotEnabled(pilotEnabled)
            .build();
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    public static boolean isColombia(int storeId)   { return inRange(storeId, COLOMBIA_MIN,   COLOMBIA_MAX); }
    public static boolean isPanama(int storeId)     { return inRange(storeId, PANAMA_MIN,     PANAMA_MAX); }
    public static boolean isGuatemala(int storeId)  { return inRange(storeId, GUATEMALA_MIN,  GUATEMALA_MAX); }
    public static boolean isCostaRica(int storeId)  { return inRange(storeId, COSTA_RICA_MIN, COSTA_RICA_MAX); }
    public static boolean isElSalvador(int storeId) { return inRange(storeId, EL_SALVADOR_MIN, EL_SALVADOR_MAX); }
    public static boolean isHonduras(int storeId)   { return HONDURAS_IDS.contains(storeId); }
    public static boolean isEcuador(int storeId)    { return inRange(storeId, ECUADOR_MIN,    ECUADOR_MAX); }
    public static boolean isBarbados(int storeId)   { return inRange(storeId, BARBADOS_MIN,   BARBADOS_MAX); }
    public static boolean isNicaragua(int storeId)  { return inRange(storeId, NICARAGUA_MIN,  NICARAGUA_MAX); }
    public static boolean isPeru(int storeId)       { return inRange(storeId, PERU_MIN,       PERU_MAX); }

    /** rate stored per item is GROSS (tax already included in price) */
    public static boolean isVatInclusive(int storeId) {
        return isGuatemala(storeId) || isPanama(storeId);
    }

    /** no IVA/VAT applied — tax factor is always 0 for these stores */
    public static boolean usesNoIva(int storeId) {
        return isPanama(storeId) || isNicaragua(storeId) || isPeru(storeId);
    }

    public static boolean isFelStore(int storeId) {
        return isCostaRica(storeId) || isElSalvador(storeId);
    }

    public static boolean usesIco(int storeId) {
        return isColombia(storeId);
    }

    // ── Capabilities value object ─────────────────────────────────────────────

    @Getter
    @Builder
    public static class Capabilities {
        private final boolean isColombia;
        private final boolean isPanama;
        private final boolean isGuatemala;
        private final boolean isCostaRica;
        private final boolean isElSalvador;
        private final boolean isHonduras;
        private final boolean isEcuador;
        private final boolean isBarbados;
        private final boolean isNicaragua;
        private final boolean isPeru;
        private final boolean isVatInclusive;
        private final boolean usesNoIva;
        private final boolean requiresFiscalStep;
        private final boolean supportsPaymentLink;
        private final boolean supportsDelivery;
        private final boolean isPilotEnabled;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static boolean inRange(int id, int min, int max) {
        return id >= min && id <= max;
    }
}
