# Models

Domain model classes mapped to DB rows via JdbcTemplate row mappers.

## Always ask before changing
Field names map directly to DB column names — a rename here requires a matching DB column rename or alias in every query that uses it.
