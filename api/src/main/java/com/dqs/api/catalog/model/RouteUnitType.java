package com.dqs.api.catalog.model;

/**
 * What a route tariff is charged per.
 *
 * In the legacy schema these were four columns on ps_rutas, so adding a tier
 * meant an ALTER and every reader had to know the column names. The database
 * holds the name as text with a CHECK constraint listing exactly these values;
 * keep the two in step.
 */
public enum RouteUnitType {
    TRIP,
    FULL_PALLET,
    HALF_PALLET,
    QUARTER_PALLET
}
