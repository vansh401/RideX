# RideX API conventions

Interactive docs: http://localhost:8080/swagger-ui.html (raw JSON: `/v3/api-docs`).
They are generated from the code, so they cannot drift. Disable both in production
(`springdoc.api-docs.enabled=false`, `springdoc.swagger-ui.enabled=false`).

## URLs
- Everything is under `/api`. Nouns, plural, lower-case: `/api/rides`, `/api/drivers`.
- "The current user" is `/me` (`/api/users/me`, later `/api/drivers/me`). The id comes from the
  verified token, never from the URL or body, so one user cannot ask for another's data.
- Actions that are not plain CRUD are `POST /{resource}/{id}/{verb}` (`.../approve`, `.../cancel`).
- Admin-only areas live under `/api/admin/**`.

## Authentication
- `Authorization: Bearer <accessToken>` on every request except `/api/auth/**` and `/api/health`.
- Access tokens last 15 minutes; use `/api/auth/refresh` with the refresh token to get new ones.

## Status codes
| Code | Meaning |
|---|---|
| 200 | OK, with a body |
| 201 | Created (`POST` that makes a resource) |
| 202 | Accepted: done quietly on purpose (`forgot-password`, `resend-verification`) |
| 204 | OK, no body (`logout`, `verify-email`, `reset-password`) |
| 400 | Validation failed or malformed body (`fieldErrors` says which fields) |
| 401 | Not logged in, or the token is invalid/expired |
| 403 | Logged in but not allowed (wrong role, suspended, not your resource) |
| 404 | Does not exist |
| 409 | Conflicts with existing data or the current state (duplicate email, invalid status move, concurrent edit) |
| 500 | Our bug. The body never contains internals; details are in the server log |

## Error body (always this shape)
```json
{
  "timestamp": "2026-10-11T17:07:34.065052Z",
  "status": 400,
  "error": "BAD_REQUEST",
  "message": "Validation failed",
  "path": "/api/auth/register",
  "fieldErrors": { "password": "must contain at least one letter and one digit" }
}
```
`fieldErrors` is `{}` when the error is not about specific fields.

## Pagination (every list endpoint)
Request: `?page=0&size=20` (page is 0-based; `size` is capped at 100).
```json
{ "content": [ ... ], "page": 0, "size": 20, "totalElements": 134, "totalPages": 7 }
```
Lists have a defined default order (documented per endpoint) so pages are stable.

## Data formats
- Times are ISO-8601 UTC instants: `2026-10-11T17:07:34Z`. Dates are `2026-06-30`.
- Money is a decimal string/number with a currency field, never a float computation (Step 3.6).
- Ids are UUIDs.
- Enums are upper-case strings (`PENDING_APPROVAL`).

## Security rules for responses
- Never return password hashes, token hashes or internal flags.
- Registration, login, reset and verification never reveal whether an email exists.
- Rate limiting for these public endpoints arrives in Phase 4 (Redis).
