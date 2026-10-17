# Auth API reference (Phase 2)

All endpoints are JSON. Public means no `Authorization` header is needed.
Errors always use the shape in [api-conventions.md](api-conventions.md).
Interactive version: `/swagger-ui.html` (generated from the code, so it is the source of truth).

| Method | Path | Auth | Success | Notable errors |
|---|---|---|---|---|
| POST | `/api/auth/register` | public | 201 | 400 validation, 409 email or phone taken |
| POST | `/api/auth/login` | public | 200 | 400, 401 wrong email or password, 403 unverified or suspended |
| POST | `/api/auth/refresh` | public | 200 | 400, 401 invalid, expired or reused token |
| POST | `/api/auth/logout` | public | 204 | 400 (always 204 for unknown tokens) |
| POST | `/api/auth/verify-email` | public | 204 | 400 invalid, used or expired link |
| POST | `/api/auth/resend-verification` | public | 202 | 400 (always 202 otherwise) |
| POST | `/api/auth/forgot-password` | public | 202 | 400 (always 202 otherwise) |
| POST | `/api/auth/reset-password` | public | 204 | 400 validation or invalid link |
| GET | `/api/users/me` | Bearer, any role | 200 | 401, 403 account not active, 404 |
| GET | `/api/health` | public | 200 | |

## Requests and responses

### `POST /api/auth/register`
```json
{ "fullName": "Asha Rao", "email": "asha@example.com", "phone": "+919876543210",
  "password": "Secret123", "role": "RIDER" }
```
`role` is `RIDER` or `DRIVER` only. Password: 8-72 characters with a letter and a digit.
Phone: 10-15 digits, optional leading `+`. Response `201`:
```json
{ "id": "…", "fullName": "Asha Rao", "email": "asha@example.com",
  "role": "RIDER", "status": "PENDING_VERIFICATION" }
```
A verification email is sent after the account is saved. No tokens are returned.

### `POST /api/auth/login`
```json
{ "email": "asha@example.com", "password": "Secret123" }
```
Response `200`:
```json
{ "accessToken": "eyJ…", "refreshToken": "…", "tokenType": "Bearer",
  "expiresInSeconds": 900,
  "user": { "id": "…", "fullName": "Asha Rao", "email": "asha@example.com", "role": "RIDER" } }
```
Use `Authorization: Bearer <accessToken>` on protected calls. The access token lasts 15 minutes.

### `POST /api/auth/refresh`
Body `{ "refreshToken": "…" }`. Returns the same shape as login with a **new** refresh token. The old one
stops working immediately; presenting it again revokes the whole session.

### `POST /api/auth/logout`
Body `{ "refreshToken": "…" }`. Ends the whole session (all tokens of that login). An access token already
issued stays valid until it expires (at most 15 minutes).

### `POST /api/auth/verify-email`
Body `{ "token": "…" }` (the value from the emailed link). Activates a pending account. Single use, valid for
15 minutes. Verifying never un-suspends a suspended account.

### `POST /api/auth/resend-verification`
Body `{ "email": "…" }`. Always `202`. Sends a new link only if the account is pending; older links stop working.

### `POST /api/auth/forgot-password`
Body `{ "email": "…" }`. Always `202`. Sends a reset link (valid 30 minutes) only for ACTIVE accounts.

### `POST /api/auth/reset-password`
Body `{ "token": "…", "newPassword": "…" }`. Sets the new password, consumes the token and revokes every
refresh token of that user.

### `GET /api/users/me`
```json
{ "id": "…", "fullName": "Asha Rao", "email": "asha@example.com", "phone": "+919876543210",
  "role": "RIDER", "status": "ACTIVE", "createdAt": "2026-10-11T17:07:34Z" }
```
Reads the account from the database, so a suspended user gets `403` even with a valid token.

## Access areas (Phase 2 state)
| Area | Rule |
|---|---|
| `/api/auth/**`, `/api/health`, API docs | public |
| `/api/admin/**` | ADMIN |
| everything else | any authenticated user |

Temporary scaffolding: `GET /api/admin/ping` (ADMIN) and `GET /api/driver/ping` (DRIVER) exist only so the
role rules can be tested. They are deleted when the real admin and driver endpoints arrive in Phase 3.

## Try it
Sample bodies live in `docs/api-samples/`. Example:
```powershell
curl.exe -s -X POST -H "Content-Type: application/json" --data "@docs/api-samples/login-ok.json" http://localhost:8080/api/auth/login
```
