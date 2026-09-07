# Azure SQL — QuoteCenter catalogs

These run against **Azure SQL (SQL Server)**, not the MySQL database the rest of
the application uses. Do not run them with the root `migration_*.sql` files and
do not copy T-SQL idioms into those, or the other way round.

## Why there are two databases

QuoteCenter's own quotation tables (`quotations`, `quotation_items`,
`quotation_delivery`, …) live in the legacy MySQL instance, because that is
where the application was grafted on. The catalogs it used to *read from the
legacy application* — clubs, routes and tariffs, exchange rates, fiscal document
types, payment methods — move here and become ours.

The point is ownership. Reading `ps_rutas` meant the DQS application decided our
schema and could change it under us. A copy we own can be shaped, indexed and
constrained properly, and it survives the legacy application being switched off.

## What this is not

**Not a live mirror.** The copy is taken once and QuoteCenter owns the rows from
that moment on. If someone edits a route tariff in the old DQS afterwards, it
does not appear here. That is the agreed trade-off, and it has a precondition:
maintenance of these catalogs has to stop happening in the legacy application.
Until that is true for a given catalog, its rows will drift.

## Order

| File | What | Size |
|---|---|---|
| `01_schema.sql` | tables, constraints, indexes, `updated_at` triggers | hand-written |
| `02_seed.sql` | country rows — reference data we define, not copied | generated |
| `03_import.sql` | one-time copy: 61 clubs, 3 route types, 434 routes, 1302 tariffs, 32 document types, 99 payment methods across 314 country rows | generated |
| `04_import_fx_history.sql` | 6076 exchange rates. **Optional** | generated |

Run them in that order. `01` is idempotent and the generated ones match on the
natural key and insert only what is missing, so a partial run can just be
repeated.

Skip `04` unless you want the history. Nothing in the application reads it — a
quote stores the rate it used — and it is twice the size of everything else put
together.

## Regenerating

`Export.java` produces `02`, `03` and `04` from a live legacy MySQL database.
Anyone with access to that database can regenerate them, which is the point:
review the generator, not a 900 KB dump someone pasted.

    cd api
    set -a; . ./.env.local; set +a          # DB_HOSTNAME / DB_USERNAME / DB_PASSWORD / DB_DATABASE
    java -cp "$(find ~/.m2 -name 'mysql-connector-j-*.jar' | head -1)" ../azure/Export.java \
        ../azure/02_seed.sql ../azure/03_import.sql ../azure/04_import_fx_history.sql

It reads credentials from the environment and never writes them into the output.

## Verified

Run end to end against SQL Server 2022 in Docker, on an empty database, in this
order, with no errors:

    countries=13  clubs=61  route_types=3  routes=434  route_prices=1302
    fiscal_document_types=32  payment_method_types=99
    country_payment_methods=314  exchange_rates=6076
    9 tables · 8 triggers · 8 foreign keys · 3 check constraints

Also checked: re-running `01`–`03` changes no counts and raises no errors; the
`updated_at` trigger advances on UPDATE; the `unit_type` check and the
`club_number` unique constraint both reject bad rows; and spot-checked route
tariffs match the legacy values (`6101 01` → 95000 / 42000).

`03_import.sql` ends with a count check that prints `Import complete.` or names
each table that came up short and fails the batch, so a partial run cannot look
like a successful one.

SQL Server 2022 is a close proxy for Azure SQL for DDL of this kind, but it is a
proxy. Run it once on a scratch Azure database before the real one.

## Rows the import omits, on purpose

Filtered out **when the script is generated**, so the expected counts are what a
correct run actually produces:

- **9 routes** belonging to clubs `6308` and `8703`, which do not exist in
  `ps_tienda` — legacy orphans. Their 27 tariffs go with them.
- **1 fiscal document type**: `ps_fel` holds `Passport` twice for Jamaica.
  `UQ_fdt_country_code` collapses them; lowest `felid` wins, which is the row
  the legacy dropdown showed first. Deduplication, not loss.

Each is listed in a comment at the top of `03_import.sql` as well.

## Decisions the import does not make for you

**`countries.price_includes_tax` reproduces current behaviour, which may not be
current intent.** It comes from `ps_tienda.impuesto_operacion`, which holds
`'+'`, `'-'` or empty rather than a boolean. Nothing documents what those mean;
the meaning is in `OrdersService::calculateCorrectTotals`:

    if ($operacion == '+')  $total = $subtotal + $tax;   // tax added on top
    else                    $total = $subtotal - $tax;   // tax already in

So `'+'` means the stored line amounts **exclude** tax, and anything else means
they **include** it. Empty falls into the `else`, so it behaves as `'-'`:

| `impuesto_operacion` | Countries | `price_includes_tax` |
|---|---|---|
| `'+'` | AW, BB, JM, NI, VI | 0 |
| `'-'` | CO, CR, DO, GT, TT | 1 |
| empty | HN, PA, SV | 1 |

**The empty three are worth a look before go-live.** Nothing distinguishes "this
country includes tax" from "nobody ever filled this field in" — both land on 1.
Panama makes the doubt concrete: the `'+'` branch carries a special adjustment
for Peru, Panama and the Dominican Republic that swaps subtotal and total, and
because Panama's value is empty that adjustment **can never run for Panama**.
The Dominican Republic is fine — it is `'-'` and the `else` branch has its own
swap — and there are no Peru clubs at all. So either Panama's data is wrong or
that code has been dead for a long time. The import copies the behaviour as it
stands; correcting it is a business decision, not a migration one.

The column was also **overloaded**: `gettoken_helper.php` reads the same field to
pick a tax *label*, mapping `'-'` to "VAT" and everything else to "IVA" — which
labels Colombia as VAT. The new schema keeps `tax_name` as its own column,
seeded from the real tax name per country, so the two concerns stop sharing a
field.

**Payment method codes are derived**, upper-cased and trimmed from
`orders_pago.descripcion`, so two spellings of the same method collapse into one
type — 314 country rows become 99 distinct types. Check the list before running
if a name was ever used to mean two different things.

## Connecting the application

The second datasource exists. It is **off by default** — with
`azure.datasource.enabled=false` no connection is opened, no catalog repository
bean is created, and the application runs entirely against MySQL exactly as
before. Developer machines and any environment not yet cut over keep working,
and a wrong connection string cannot take down an environment that was not
using it.

    AZURE_DB_ENABLED=true
    AZURE_DB_URL=jdbc:sqlserver://<server>.database.windows.net:1433;databaseName=quotecenter;encrypt=true
    AZURE_DB_USERNAME=...
    AZURE_DB_PASSWORD=...

With it on, `GET /api/v1/diag/catalog` reports a row count per table and
`/api/v1/diag/catalog/country/CR` resolves a country with its current rate,
payment methods and document types. Those endpoints exist for exactly this: the
catalogs have no consumer yet, so without them nothing would notice a broken
connection until the day something is switched over.

### Trying it locally, without an Azure instance

SQL Server in Docker is close enough to Azure SQL for this schema:

    docker run -d --name dqs-mssql --platform linux/amd64 \
      -e ACCEPT_EULA=Y -e MSSQL_SA_PASSWORD='<password>' -e MSSQL_PID=Developer \
      -p 11433:1433 mcr.microsoft.com/mssql/server:2022-latest

    docker exec dqs-mssql /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P '<password>' -C \
      -Q "CREATE DATABASE quotecenter"
    # then copy in and run 01, 02, 03 (and 04 if you want the rates)

    AZURE_DB_URL=jdbc:sqlserver://localhost:11433;databaseName=quotecenter;encrypt=true;trustServerCertificate=true

`trustServerCertificate=true` is for the container's self-signed certificate.
Never set it against a real Azure instance.

## What still reads the legacy database

**The services have not been switched over.** They still read `ps_tienda`,
`ps_rutas`, `ps_fel`, `orders_pago` and `ps_tasa_cambio` through
`NativeQueries` against MySQL. The Azure side is wired and proven but has no
consumer, which is why the flag defaults to off and nothing injects a catalog
repository outside the diagnostics controller.

Two queries join a legacy table to a QuoteCenter table and cannot simply be
repointed, since the two will live in different databases:

- `DeliveryLogRepository` — `ps_delivery_log_cargue` with `quotation_delivery`
- `PriceSmartPaymentService` — `ps_tienda` with `quotations` / `quotation_customers`

Both need composing in the service instead of in SQL — the two databases cannot
be joined, and no relationship may be declared between a catalog entity and one
in `com.dqs.api.model`.

Members (`ps_socios`, `ps_socios_dqs20`) are deliberately **not** here. They are
live data, not a catalog, and there is already a Business API for membership
lookup — copying them would create a second stale source of truth.

## Differences from the MySQL blueprint

Ported from `migration_quotecenter_schema.sql`, with the translations T-SQL
forces:

| MySQL | Here |
|---|---|
| `AUTO_INCREMENT` | `IDENTITY(1,1)` |
| `TINYINT(1)` | `BIT` |
| `ENUM(...)` | `NVARCHAR` + `CHECK` |
| `TIMESTAMP ... ON UPDATE CURRENT_TIMESTAMP` | `DATETIMEOFFSET(3)` + an `AFTER UPDATE` trigger |
| `VARCHAR` | `NVARCHAR` — names carry accents |
| inline `COMMENT` | `--` comments |

Timestamps are `DATETIMEOFFSET`, not `DATETIME2`: they are written with
`SYSUTCDATETIME()` so the offset is `+00:00`, and the entities map them to
`java.time.Instant`, which Hibernate's SQL Server dialect will only accept
against `DATETIMEOFFSET`. A `DATETIME2` column fails schema validation at
startup — which is how this was found.

`ON UPDATE CURRENT_TIMESTAMP` has no declarative equivalent, so each table with
an `updated_at` gets a small trigger. The alternative — letting Hibernate write
it — was rejected: the import script and any manual correction would then leave
`updated_at` stale, which is exactly when you want it accurate.

## Foreign keys that could not follow

`exchange_rates.created_by_user_id` references `users`, which is in MySQL. It
stays as a plain id with no constraint. A cross-database foreign key is not
something Azure SQL can enforce, and pretending otherwise in the DDL would be
worse than the honest gap.
