# DQS Backend — Global Rules

## Stack
- Spring Boot 3, Java 21, JdbcTemplate (no JPA/Hibernate), MySQL
- Run: `./mvnw spring-boot:run` from `dqs-backend/api/`

## Migrations
- SQL migration files live in `dqs-backend/migration_*.sql`
- Always create a new migration file — never edit existing ones.

## Code Conventions
- `@RequiredArgsConstructor` + `@Builder` on all classes that need injection or construction
- No `@Autowired` — constructor injection only (via `@RequiredArgsConstructor`)
- No JPA/Hibernate — all DB access via `JdbcTemplate`
- All variables, methods, and code in English
- `ClubCapabilities.java` is the source of truth for per-club feature flags — always check it before adding club/country-specific conditionals anywhere
