# DQS Backend — Global Rules

## Stack
- Spring Boot 3, Java 21, Spring Data JPA (no JdbcTemplate), MySQL
- Run: `./mvnw spring-boot:run` from `dqs-backend/api/`

## Migrations
- SQL migration files live in `dqs-backend/migration_*.sql`
- Always create a new migration file — never edit existing ones.

## Code Conventions
- `@RequiredArgsConstructor` + `@Builder` on all classes that need injection or construction
- No `@Autowired` — constructor injection only (via `@RequiredArgsConstructor`)
- Spring Data JPA for tables we own; native SQL through
  `repository/support/NativeQueries` for the legacy `ps_*` tables, which never
  get an `@Entity`. There is no `JdbcTemplate` in this codebase — do not add
  one. `api/src/main/java/com/dqs/api/repository/CLAUDE.md` has the full rule
  and the reasoning
- All variables, methods, and code in English
- `ClubCapabilities.java` is the source of truth for per-club feature flags — always check it before adding club/country-specific conditionals anywhere

## Business rules
- 