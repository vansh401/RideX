# Auth API reference (Phase 2, updated in Phase 4)

All endpoints are JSON. Public means no `Authorization` header is needed.
Errors always use the shape in [api-conventions.md](api-conventions.md).
Interactive version: `/swagger-ui.html` (generated from the code, so it is the source of truth).

| Method | Path | Auth | Success | Notable errors |
|---|---|---|---|---|
| POST | `/api/auth/register` | public | 201 | 400 validation, 409 email or phone taken |
| POST | `/api/auth/login` | public | 200 | 400, 401 wrong email or password, 403 unverified or suspended, 429 too many failed attempts |
| POST | `/api/auth/refresh` | public | 200 | 400, 401 invalid, expired or reused token |
| POST | `/api/auth/logout` | public (send the access token too) | 204 | 400 (always 204 for unknown tokens) |
| POST | `/api/auth/verify-email` | public | 204 | 400 invalid, used or expired link |
| POST | `/api/auth/verify-otp` | public | 204 | 400 wrong, expired or used code (and too many guesses) |
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
Body `{ "refreshToken": "…" }`. Ends the whole session (all tokens of that login). If the request also carries
the access token (`Authorization: Bearer …`), that token is cancelled immediately (Redis revocation, Phase 4);
otherwise it would stay valid until it expires (at most 15 minutes).

### `POST /api/auth/verify-email`
Body `{ "token": "…" }` (the value from the emailed link). Activates a pending account. Single use, valid for
15 minutes. Verifying never un-suspends a suspended account.

### `POST /api/auth/verify-otp`
Body `{ "email": "…", "code": "123456" }` (the 6 digits from the verification email). Alternative to the link.
The code lives in Redis for 10 minutes, works once, and allows 5 wrong guesses, after which even the right code
is refused and a new one must be requested. An unknown email and a wrong code give the same `400`.

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

## Rate limiting (Phase 4)

Every `/api/**` call is counted per client address in Redis. Beyond the limit the answer is `429 Too Many Requests`
with a `Retry-After` header (seconds) and the usual JSON error body.

| Scope | Default |
|---|---|
| register, forgot-password, resend-verification, verify-otp | 5 per hour |
| every other `/api/auth/**` call | 30 per minute |
| every other `/api/**` call | 600 per minute |
| login: wrong passwords for one email from one address | 5, then locked for 15 minutes (even for the correct password) |

All values are in `application.properties` (`ridex.rate-limit.*`).

## Access tokens are cancellable (Phase 4)

An access token is rejected with `401` right away (not after 15 minutes) when its user was suspended, had a
password reset, or logged out with it. See [redis-concepts.md](redis-concepts.md) section 4.8.

## Tokens are signed with a private key (Phase 7)

Access tokens used to be signed with a secret shared by everything that verified them. Now that there are several services, that
would let any of them forge a token. Since Phase 7 tokens use **RS256**: the core signs with a private RSA key that never leaves
it, and publishes the matching public key.

| | |
|---|---|
| Public key (JWKS) | `GET /.well-known/jwks.json`, public, cacheable. Other services use it to verify tokens |
| Token header | `alg: RS256`, `kid: <fingerprint of the public key>` (so keys can be rotated) |
| Claims | `sub` (user id), `role`, `iss` (`ridex`), `jti`, `iat`, `exp`, `iat_ms` |
| Private key | created on the first start in `data/keys/jwt-signing-key.pem` (git-ignored; never commit it) |
| `JWT_SECRET` | **no longer used** |

**Everyone is logged out once** when you first start the new version: tokens signed the old way do not verify with the new key.
Log in again.

A token is accepted by the core and by every service (notification, payment, pricing, analytics) in exactly the same way:
signature, expiry, issuer, and the shared Redis revocation list. See [microservices-concepts.md](microservices-concepts.md) section 3.

### Endpoints that moved to other services

The browser still uses one address; the gateway (Vite proxy in development) routes by path. The URLs and JSON are unchanged.

| Path | Now served by |
|---|---|
| `/api/notifications/**` | notification-service (:8081) |
| `/api/payments/**`, `/api/admin/payments/**` | payment-service (:8082) |
| `/api/fares/**` | pricing-service (:8083) |
| `/api/admin/events/{audit,dead-letters,lag}`, `/api/admin/analytics/**` | analytics-service (:8084) |
| `/api/admin/events/outbox` | **replaced by** `/api/admin/outbox` (core) and `/api/admin/payments/outbox` (payment) |

New behaviour of the core: `POST /api/rides` answers `409` while the rider has an unpaid ride (the compensation step of the
ride-payment saga), and `503` with `Retry-After` while the pricing service is unavailable.
