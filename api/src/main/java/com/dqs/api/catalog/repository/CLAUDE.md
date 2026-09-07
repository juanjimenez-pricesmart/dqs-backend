# Catalog repositories

Spring Data repositories over `com.dqs.api.catalog.model` — the QuoteCenter
tables that replace the legacy catalogs.

**Derived query methods, not SQL.** These tables are ours and properly modelled,
so `findByClub_ClubNumberAndActiveTrueOrderByCode` does the job a hand-written
SELECT used to. Reach for `@Query` only when a derived name would be unreadable,
and write JPQL when you do. `NativeQueries` exists for the legacy `ps_*` tables
and nothing else.

Use `@EntityGraph` where a caller needs children — routes with their tariffs, in
particular — rather than letting lazy loading issue a query per row.

**Nothing may inject these outside a `CatalogSource` implementation** while both
sets of tables are live. A service that reads them directly bypasses the flag
that lets an environment fall back to the legacy tables.
