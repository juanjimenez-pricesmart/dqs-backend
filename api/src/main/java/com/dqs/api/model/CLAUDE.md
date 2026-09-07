# Models

JPA entities: `@Entity` + `@Table`, Lombok `@Getter @Setter @NoArgsConstructor
@AllArgsConstructor @Builder`, one class per table we own. They used to be plain
row-mapper targets; they are mapped entities now.

**Only for tables we own.** Never map a legacy `ps_*` / `orders_*` table — see
`repository/CLAUDE.md` for why, and use `NativeQueries` for those instead.

## Writing one

- Take the column list from `SHOW COLUMNS`, not from the migration files. They
  have drifted before.
- Name every column explicitly: `@Column(name = "sign_price")`. Do not rely on
  the naming strategy.
- Associations follow the house style: `@ManyToOne(fetch = FetchType.LAZY)` with
  `@JoinColumn(name = "quotation_id")`, or `@OneToOne` where the FK is unique.
  Use `getReferenceById` to attach a parent without loading it.
- Columns the database fills — `created_at`, `updated_at` with
  `DEFAULT CURRENT_TIMESTAMP` — get `insertable = false, updatable = false`, or
  Hibernate will overwrite them with nulls.
- `TINYINT(1)` reaches the driver as `BIT`, so it must be mapped as `Boolean`.
  An `Integer` there fails schema validation and the application will not start.
  Convert to 0/1 at the service edge if the API carries it as a number — that is
  what `QuotationFiscal.documentValidated` does.

## Always ask before changing

Field names map to DB column names, and `spring.jpa.hibernate.ddl-auto` is
`validate`: a mapping that does not match the live schema does not fail at the
query, it stops the whole application from starting. A rename needs the matching
column rename and a migration file.

Adding a field is safe. Renaming or removing one is not — the API response keys
are built from these, and the frontend reads them.
