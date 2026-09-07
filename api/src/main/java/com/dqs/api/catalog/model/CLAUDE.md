# Catalog entities (Azure SQL)

Tables QuoteCenter owns, in the Azure SQL database — countries, clubs, routes
and tariffs, fiscal document types, payment methods, exchange rates. They
replace what used to be read out of the legacy application's `ps_*` tables.

**This package must stay outside `com.dqs.api.model`.** Entity scanning is
recursive, so a subpackage there would also be picked up by the MySQL
persistence unit, which would try to validate these tables against MySQL and
stop the application from starting.

Both persistence units run `ddl-auto=validate`, so a mapping that disagrees with
`azure/01_schema.sql` fails at boot, not at the query. Change the two together.

Do not add a relationship between an entity here and one in `com.dqs.api.model`:
they are in different databases and Hibernate cannot join them. Compose in the
service instead.
