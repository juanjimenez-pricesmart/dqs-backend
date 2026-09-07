# Repositories

**JPA is the default. There is no JdbcTemplate in this codebase — do not add one.**

Which of the two tools you reach for is decided by one question: **do we own the
table?**

## Tables we own — Spring Data JPA

Anything our own `migration_*.sql` files create: `quotations`, `quotation_items`,
`quotation_customers`, `quotation_totals`, `quotation_delivery`,
`quotation_fiscal`, `quotation_payment`, `quotation_payment_attempts`,
`quotation_cancel_reasons`, `cities`, `zones`, `neighborhoods`,
`economic_activities`, `users`, `ps_globalpay_credenciales` (that one carries the
legacy prefix but is ours — check the migrations, not the name).

Give it an `@Entity` in `model/` and an interface extending `JpaRepository`.
Derived query methods where they read well, `@Query` with JPQL where they do not.

## Legacy tables — native SQL through `NativeQueries`

`ps_rutas`, `ps_tipos_ruta`, `ps_fel`, `ps_socios`, `ps_socios_fel`,
`ps_socios_dqs20`, `ps_tienda`, `ps_tasa_cambio`, `ps_cierre_mensual`, `ps_mes`,
`ps_delivery_log_cargue`, `orders_pago` — everything belonging to the DQS
application we are replacing.

Use `repository/support/NativeQueries`: `list`, `first`, `column`, `scalar`,
`update`, `insertReturningKey`. It goes through the same EntityManager and the
same transaction, and returns insertion-ordered maps keyed by the column label
exactly as the SELECT spells it.

**Never put an `@Entity` on a legacy table.** `spring.jpa.hibernate.ddl-auto` is
`validate`, so every entity is checked against the live schema at startup. We do
not own those tables and cannot stop the legacy team altering them; an entity
over one of theirs turns a column rename on their side into a backend that will
not start — quotations, invoicing, all of it down, over a table one endpoint
reads. Native SQL keeps the blast radius at that one query.

## Schema changes

Any schema change needs a matching `dqs-backend/migration_*.sql`. This is not
paperwork: `quotation_delivery` ran for months with three columns
(`route_name`, `pallets`, `logcargueid`) that existed in the database and in no
migration, and that gap became a hard blocker the moment the table was mapped —
under `validate` the entity has to match the live table exactly. When in doubt,
build the mapping from `SHOW COLUMNS`, not from the migration files.

## Always ask before changing

- **Response shape.** These repositories and the services over them return maps
  whose keys are the column labels, in snake_case, and the frontend reads those
  keys directly (`src/api/deliveries.ts`, `src/api/fiscal.ts`). Renaming a key —
  including by returning an entity and letting Jackson camel-case it — is a
  breaking change. Persistence and contract are separate decisions.
- **SQL semantics.** Data integrity, and not easily reversible in production.
