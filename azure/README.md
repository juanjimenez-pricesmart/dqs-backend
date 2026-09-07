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
| `03_import.sql` | one-time copy: 61 clubs, 3 route types, 443 routes, 1329 tariffs, 33 document types, 99 payment methods across 314 country rows | generated |
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

## Decisions the import does not make for you

**`countries.price_includes_tax` is left at 0 for every country.** It comes from
`ps_tienda.impuesto_operacion`, which holds `'+'`, `'-'` or empty rather than a
boolean, and nothing in either codebase documents what those mean. It feeds the
OMS tax payload, so it is a deliberate decision, not something to infer from a
sign. Each country's raw legacy value is in a comment above its row in
`02_seed.sql`.

**Payment method codes are derived**, upper-cased and trimmed from
`orders_pago.descripcion`, so two spellings of the same method collapse into one
type — 314 country rows become 99 distinct types. Check the list before running
if a name was ever used to mean two different things.

## What still reads the legacy database

This is schema and data only. The application code has not been switched over —
it still reads `ps_tienda`, `ps_rutas`, `ps_fel` and the rest through
`NativeQueries`, because there is no Azure connection configured yet. Switching
it needs the instance, credentials and a second datasource.

Two queries join a legacy table to a QuoteCenter table and cannot simply be
repointed, since the two will live in different databases:

- `DeliveryLogRepository` — `ps_delivery_log_cargue` with `quotation_delivery`
- `PriceSmartPaymentService` — `ps_tienda` with `quotations` / `quotation_customers`

Both need composing in the service instead of in SQL.

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
| `TIMESTAMP ... ON UPDATE CURRENT_TIMESTAMP` | `DATETIME2(3)` + an `AFTER UPDATE` trigger |
| `VARCHAR` | `NVARCHAR` — names carry accents |
| inline `COMMENT` | `--` comments |

`ON UPDATE CURRENT_TIMESTAMP` has no declarative equivalent, so each table with
an `updated_at` gets a small trigger. The alternative — letting Hibernate write
it — was rejected: the import script and any manual correction would then leave
`updated_at` stale, which is exactly when you want it accurate.

## Foreign keys that could not follow

`exchange_rates.created_by_user_id` references `users`, which is in MySQL. It
stays as a plain id with no constraint. A cross-database foreign key is not
something Azure SQL can enforce, and pretending otherwise in the DDL would be
worse than the honest gap.
