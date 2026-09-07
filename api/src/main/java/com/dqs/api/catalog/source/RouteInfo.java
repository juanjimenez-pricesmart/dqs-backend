package com.dqs.api.catalog.source;

/**
 * A delivery route as the delivery panel needs it.
 *
 * The component names are the JSON keys the frontend already reads
 * (DeliveryPanel.tsx), so this serialises exactly as the old Map did.
 *
 * {@code id} is the route key — the legacy `llave`, "6101 01" — and not a
 * surrogate. It is what {@code quotation_delivery.route_id} stores, so it has to
 * stay this value on both sides of the switch.
 */
public record RouteInfo(
    String  id,
    String  name,
    Double  truckSize,
    Double  palletRate,
    Integer palletMinimum,
    Double  halfPalletRate,
    Integer halfPalletMinimum,
    Double  quarterPalletRate,
    Double  quarterPalletRateUsd,
    String  routeTypeName,
    String  routeTypeCode
) {}
