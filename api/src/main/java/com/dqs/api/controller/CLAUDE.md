# Controllers

Thin REST layer — no business logic here, only routing and response shaping.

## Always ask before changing
- Adding new endpoints or changing URL paths — frontend must be updated in sync
- Modifying response status codes or error shapes
- Changing request body structure — these are API contracts consumed by the frontend
