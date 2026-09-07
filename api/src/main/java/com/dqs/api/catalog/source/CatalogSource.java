package com.dqs.api.catalog.source;

import java.util.List;

/**
 * Where catalog data comes from — the legacy database, or ours in Azure.
 *
 * <h2>Why this exists</h2>
 *
 * The two cannot simply be swapped by editing a query. Environments cut over at
 * different times: a developer machine may have no Azure instance at all while
 * staging is already on it. So the choice is a configuration flag, not a
 * deployment — {@code azure.datasource.enabled} picks the implementation, and
 * exactly one bean exists either way.
 *
 * Both implementations return identical shapes, so nothing above this line can
 * tell which one it is talking to. That is the whole point: the switch has to be
 * reversible in an environment where something turns out to be wrong.
 *
 * <h2>What is not here yet</h2>
 *
 * Clubs and exchange rates. They feed the OMS payload context, which carries the
 * legacy {@code impuesto_operacion} value, and its meaning for the three
 * countries whose value is empty is still an open question — see
 * azure/README.md. Switching them before that is settled would move a decision
 * nobody has taken.
 *
 * <h2>When the legacy database is switched off</h2>
 *
 * Delete {@link LegacyCatalogSource}, drop the interface, and let the services
 * inject the repositories directly. This seam is scaffolding with a known end.
 */
public interface CatalogSource {

    /** Active delivery routes for a club, with their tariffs. */
    List<RouteInfo> routesOfClub(Integer clubNumber);

    /** Fiscal document types offered in a country. */
    List<DocTypeInfo> documentTypesOfCountry(String countryIso2);

    /** Payment methods available in a country. */
    List<PaymentMethodInfo> paymentMethodsOfCountry(String countryIso2);

    /** Which implementation is live, for diagnostics and logging. */
    String describe();
}
