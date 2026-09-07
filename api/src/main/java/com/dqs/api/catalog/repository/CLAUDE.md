# Catalog repositories (Azure SQL)

Spring Data repositories over `com.dqs.api.catalog.model`, bound to the Azure
persistence unit by `AzureCatalogDataSourceConfig`.

**Derived query methods, not SQL.** These tables are ours and properly modelled,
so `findByClub_ClubNumberAndActiveTrueOrderByCode` does the job that a hand
-written SELECT used to. Reach for `@Query` only when a derived name would be
unreadable, and write JPQL when you do. Native SQL here would defeat the point
of owning the schema; `NativeQueries` exists for the legacy tables and is bound
to the other datasource anyway.

**Nothing may inject these unconditionally yet.** The whole Azure configuration
is behind `azure.datasource.enabled`, false by default. A service that requires
one of these beans makes Azure a hard startup requirement for every environment,
including developer machines that have no instance.

Use `@EntityGraph` where a caller needs children — routes with their tariffs, in
particular — rather than letting lazy loading issue a query per row.
