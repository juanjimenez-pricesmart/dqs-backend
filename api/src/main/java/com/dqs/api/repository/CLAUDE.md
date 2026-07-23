# Repositories

JdbcTemplate SQL queries — direct DB access, no ORM.

## Always ask before changing
SQL changes affect data integrity and are not easily reversible in production.
Any schema change must be accompanied by a new migration file in `dqs-backend/migration_*.sql`.
