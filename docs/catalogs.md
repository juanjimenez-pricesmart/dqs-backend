# QuoteCenter catalogs

Clubs, delivery routes and their tariffs, fiscal document types, payment methods
and exchange rates. QuoteCenter used to read all of these out of the legacy
application's own tables. They are ours now.

## Why

Reading `ps_rutas` meant the DQS application decided our schema and could change
it under us. It also meant carrying its shape: tariffs as four columns on the
route, so adding a tier was an `ALTER`; payment method names repeated on every
country row, so two spellings of the same method both exist; exchange rates
scoped per store, a dimension every caller ignored.

Ownership is the point, not location. The legacy application does not know these
tables exist.

## Where

**The same MySQL database as everything else** — `quotes_dqs_osys_dev`.

An Azure SQL database was built for this first, and abandoned. What it cost was
not the port to T-SQL but the split: two engines mean no joins across them, and
two of our queries cross the boundary. `DeliveryLogRepository` joins
`ps_delivery_log_cargue` to `quotation_delivery`, and `PriceSmartPaymentService`
joins `ps_tienda` to `quotations`. In one database those stay SQL; across two
they become application code that pages one side and matches it against the
other, for no gain.

Being in the same database as the legacy tables is not the same as being theirs.
The move that matters is off `ps_*`, and that has happened.

Moving to Azure later stays open, and is easier from here than from where we
were: a clean, normalised schema is a thing you can move. That exercise was done
end to end — schema, import and application — before it was rolled back, so the
shape of that work is known.

## Files

    migration_quotecenter_catalogs.sql          8 tables, plus 3 columns on countries
    migration_quotecenter_catalogs_import.sql   one-time copy out of the ps_* tables

Both are idempotent; run them in that order. The import is plain
`INSERT .. SELECT`, since source and target are in the same database.

Fourteen tables from `migration_quotecenter_schema.sql` already existed —
`countries` (already holding the thirteen countries, with `code` as the ISO2),
`cities`, `zones`, `neighborhoods`, `economic_activities` and the quotation core.
This is the rest of a design that was written and half-landed, not a new one.

## Switching over

`CatalogSource` has two implementations and a flag picks one:

    quotecenter.catalogs.own-tables=false   # legacy ps_* tables (default)
    quotecenter.catalogs.own-tables=true    # our tables

Exactly one bean exists either way and the services cannot tell which. Both sets
of tables are populated, so moving an environment across is a restart, not a
deployment, and reversible if something looks wrong.

Switched: **routes and tariffs**, **fiscal document types**, **payment methods**.

Not switched: **clubs** and **exchange rates**. Their tables are populated, but
they feed the OMS payload context, which carries `impuesto_operacion` — see the
open question below. `ps_delivery_log_cargue` stays legacy entirely.

With the flag on, `GET /api/v1/diag/catalog` reports a row count per table and
`/api/v1/diag/catalog/country/CR` resolves a country with its current rate,
payment methods and document types.

## Identifiers that had to survive

Some catalog values are **stored on quotations**, so regenerating them would
orphan existing records. Checked one by one against live data:

| Catalog | What a quotation stores | How it survives |
|---|---|---|
| Routes | `quotation_delivery.route_id` = the legacy `llave`, `6101 01` | kept as `routes.code` |
| Payment methods | `quotation_payment.payment_method_id` = the **tender key**, not `pago_id` | kept as `country_payment_methods.tender_key` |
| Fiscal document types | `quotation_fiscal.document_type` = the **`felid`**, not the code | the import assigns it as the explicit `id` |

That last one is why `fiscal_document_types.id` has no `AUTO_INCREMENT`. The
fiscal form's select carries `felid` as the option value, so a generated id would
have left every saved fiscal record pointing at whatever type landed on its
number.

`country_payment_methods.id` does differ from `orders_pago.pago_id`, and that is
safe: the frontend uses it as a list key and nothing else.

## What the data was carrying

Two things the design had wrong, found by diffing the two sources
endpoint by endpoint:

**`pallet_required` and `halfpallet_required` are minimum quantities**, not
flags — 4 full pallets, 15 half. The 3NF draft modelled them as booleans on the
route, which throws the numbers away, and the delivery panel prints them. They
are `route_prices.minimum_quantity`, which is where they belong: the minimum is a
property of the tier, which is why the quarter-pallet tier has none.

**Payment methods came out in a different order.** `sort_order` was first
imported as `pago_id`, an insertion order, while legacy sorts the dropdown
alphabetically. Every row imports with `sort_order` 0 and the reader falls back
to the method name; set a non-zero value to move a row.

## Rows the import leaves behind

- **9 routes** of clubs `6308` and `8703`, which are not in `ps_tienda`. Legacy
  orphans; their 27 tariffs go with them.
- **1 document type**: `ps_fel` holds `Passport` twice for Jamaica (felid 23 and
  31). 23 wins. Neither is referenced by any quotation, and the values actually
  in use — 1 (Costa Rica PHYSICAL) and 19 (Colombia ID) — both survive.

## Open question: `price_includes_tax`

It comes from `ps_tienda.impuesto_operacion`, which holds `'+'`, `'-'` or empty
rather than a boolean. The meaning is not written down anywhere; it is in
`OrdersService::calculateCorrectTotals`:

```php
if ($operacion == '+')  $total = $subtotal + $tax;   // tax added on top
else                    $total = $subtotal - $tax;   // tax already in
```

So `'+'` means stored line amounts **exclude** tax, and anything else means they
**include** it. Empty falls into the `else`:

| `impuesto_operacion` | Countries | `price_includes_tax` |
|---|---|---|
| `'+'` | AW, BB, JM, NI, VI | 0 |
| `'-'` | CO, CR, DO, GT, TT | 1 |
| empty | HN, PA, SV | 1 |

**The empty three need a decision before go-live.** Nothing distinguishes "this
country includes tax" from "nobody ever filled the field in" — both land on 1.
Panama makes it concrete: the `'+'` branch carries a subtotal/total swap for
Peru, Panama and the Dominican Republic, and because Panama's value is empty
**that swap can never run for Panama**. The Dominican Republic is fine (it is
`'-'` and the `else` branch has its own swap), and there are no Peru clubs at
all. So either Panama's data is wrong or that code has been dead for years.

The import copies the behaviour as it stands. Correcting it is a business
decision, not a migration one. Clubs and exchange rates stay on the legacy side
until it is taken, because they are what feed the OMS tax payload.

The column was also **overloaded**: `gettoken_helper.php` reads the same field to
pick a tax *label*, mapping `'-'` to "VAT" and everything else to "IVA", which
labels Colombia as VAT. `countries.tax_name` is its own column now.

## The precondition nobody can enforce in code

The copy is taken once. Nothing keeps it in step with the legacy tables
afterwards. That is the agreed trade-off, and it has a condition: **maintenance
of each catalog has to stop happening in the old DQS.** Edit a route tariff over
there and it does not appear here — no error, no warning, just a stale row.
