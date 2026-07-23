# DTOs

Request and response objects — the shared contract between frontend and backend.

## Always ask before changing
- Renaming or removing a field is a breaking change on both sides
- Adding a required field to a request DTO breaks existing callers
- Changing a response field name breaks frontend deserialization silently
