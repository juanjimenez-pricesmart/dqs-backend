package com.dqs.api.util;

import java.util.Map;
import java.util.Set;

/**
 * Registry of product codes that carry behavior of their own.
 *
 * A special item is not just a SKU with a magic number: it is a line the rest
 * of the system has to treat differently — excluded from the line totals,
 * created and destroyed by its own panel rather than a plain add, and so on.
 * Declaring those traits here means adding the next special item is one entry,
 * not a hunt for every `equals("888905")` in the codebase.
 *
 * Legacy reached the same conclusion: application/helpers/specialitems_helper.php
 * exists precisely to "evitar hardcodear IDs de productos en el código". This
 * mirrors it, with the traits made explicit so callers ask what an item *does*
 * rather than which code it is.
 *
 * The frontend keeps a matching registry in src/constants/specialItems.ts.
 * Add a new item to both.
 */
public final class SpecialItems {

    private SpecialItems() {}

    /** Delivery charge. Its line is created and removed by DeliveryService. */
    public static final String DELIVERY = "888905";

    /** Gift card. Its price is chosen from a preset list, not the catalog. */
    public static final String GIFT_CARD = "999979";

    /** Traits a special item can carry. */
    public enum Trait {
        /**
         * The line's money is accounted for elsewhere — for delivery, in
         * quotation_delivery.amount — so counting it again in the line totals
         * would double it.
         */
        EXCLUDED_FROM_LINE_TOTALS,
        /**
         * The line belongs to a dedicated panel, which owns creating and
         * deleting it. Adding it through a plain item add leaves a row with no
         * backing record, which no screen can then edit.
         */
        OWNED_BY_PANEL,
        /** Price comes from a preset list rather than the catalog. */
        PRESET_AMOUNT,
    }

    private static final Map<String, Set<Trait>> REGISTRY = Map.of(
        DELIVERY,  Set.of(Trait.EXCLUDED_FROM_LINE_TOTALS, Trait.OWNED_BY_PANEL),
        GIFT_CARD, Set.of(Trait.PRESET_AMOUNT)
    );

    public static boolean isSpecial(String productId) {
        return productId != null && REGISTRY.containsKey(productId);
    }

    public static boolean has(String productId, Trait trait) {
        return productId != null && REGISTRY.getOrDefault(productId, Set.of()).contains(trait);
    }

    public static boolean isDelivery(String productId) {
        return DELIVERY.equals(productId);
    }

    public static boolean isGiftCard(String productId) {
        return GIFT_CARD.equals(productId);
    }

    /** True for an ordinary line — the one that counts toward the line totals. */
    public static boolean isRegularLine(String productId) {
        return !has(productId, Trait.EXCLUDED_FROM_LINE_TOTALS);
    }
}
