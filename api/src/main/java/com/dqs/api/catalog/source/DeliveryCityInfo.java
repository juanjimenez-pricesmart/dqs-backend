package com.dqs.api.catalog.source;

/**
 * A delivery city as the delivery panel needs it.
 *
 * {@code id} is a String on purpose. DeliveryPanel.tsx types the option as
 * {@code {id: string; name: string}} and matches the selected value with
 * {@code cities.find(c => c.id === cityId)} against a {@code <select>} value,
 * which is always a string. A numeric id would serialise as a number, the
 * strict comparison would never hold, and the city name would silently stop
 * being sent with the save.
 */
public record DeliveryCityInfo(
    String id,
    String name
) {}
