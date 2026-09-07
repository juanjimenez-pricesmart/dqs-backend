# Catalog entities

Tables QuoteCenter owns that replace what used to be read out of the legacy
application: clubs, routes and tariffs, fiscal document types, payment methods,
exchange rates, and the countries they hang off.

They live in the same database as everything else, in the same persistence unit,
and are scanned by the ordinary Spring Boot autoconfiguration. The package is
separate for the sake of the reader, not because of any wiring.

`ddl-auto=validate` is on, so a mapping that disagrees with
`migration_quotecenter_catalogs.sql` stops the application from starting rather
than failing at the query. Change the two together, and take the column list
from `SHOW COLUMNS` — the migration files have drifted from the database before.

Two ids here are **persisted on quotations** and must never be regenerated:
`FiscalDocumentType.id` is the legacy felid, stored in
`quotation_fiscal.document_type`; `Route.code` is the legacy llave, stored in
`quotation_delivery.route_id`. `CountryPaymentMethod.tenderKey` is likewise what
`quotation_payment.payment_method_id` holds.

Do not add a relationship from one of these to a legacy `ps_*` table. Those are
never mapped — see repository/CLAUDE.md.
