# PBL6-26 — Auth UI Web

The web frontend calls the real services through `/api` on the same origin. Vite proxies
to Gateway in development; nginx does the same in the frontend container. Google OAuth
remains deferred under PR #10, and native mobile is outside this ticket. The web layouts
remain responsive. There is no remember-me control or user-selectable account role.

## Run locally

```powershell
cd frontend
npm ci
npm run dev
```

Start Gateway, Auth, User and Shop with a shared `AUTH_JWT_SECRET` and PostgreSQL/RabbitMQ.
For local HTTP, use `AUTH_REFRESH_COOKIE_SECURE=false`. Refresh cookies are HttpOnly and
are never copied into localStorage/sessionStorage. Access tokens exist only in memory.
The only persisted session metadata is the non-secret `vmarket-auth-revision` counter,
which orders cookie changes between tabs under Web Locks. It contains no credentials or
profile data. When storage is unavailable, tabs reconcile through the server cookie.

Login accepts username or email in a single field and sends `{identifier, password}`.
Auth still accepts the legacy `{email, password}` payload. Email is case-insensitive;
username preserves its registration case. Both lookup paths lock the same user row and
share failed-attempt counters, temporary locks and administrative suspension. Password
recovery and OTP still use email; a username is not prefilled into password recovery.

Registration includes username, name and phone. The Auth registration DTO receives only
email, username and password. OTP verification creates the session; the frontend then
loads and updates the User profile while preserving its other fields. A failed profile
save offers retry or postponement without creating the account again. Reloading during
registration discards the unsaved name/phone; they can be entered on `/profile` later.

## Automated checks

```powershell
cd frontend
npm run lint
npm run format:check
npm run test
npm run build
```

Gateway regression tests bind the allowlist directly from `application.yml`. They verify
anonymous POST access to OTP request/verify and password forgot/reset, strip spoofed user
headers, and reject protected or suffixed Auth paths without a bearer token.

```powershell
cd services
.\mvnw.cmd -pl api-gateway -am test
```

Use Java 17 for Maven. The backend Dockerfiles build and run Java 17 regardless of the
host JDK.

## Real-backend acceptance tests

The dedicated Compose file creates an isolated stack from current source with disposable
PostgreSQL **tmpfs** storage. It does not mount the normal development databases or need
the root `.env`/MinIO configuration. Keep the `vmarket-auth-e2e` project name: the fixture
helper refuses to write data unless the project matches and PostgreSQL uses tmpfs.
Fixtures modify rows only; Flyway alone owns schema creation.

From the repository root:

```powershell
docker compose -p vmarket-auth-e2e -f frontend/e2e/compose.yml up -d --build --wait --wait-timeout 300
cd frontend
npx playwright install chromium
npm run test:e2e
```

When a system Chrome/Edge browser is already installed, set `E2E_BROWSER_CHANNEL=chrome`
or `msedge` to use it instead of downloading Chromium. CI uses Playwright Chromium.

Playwright starts Vite at `http://127.0.0.1:5173` and uses Gateway on port 8080. Existing
Vite instances may be reused outside CI. If another server is running on 5173, ensure it
serves this checkout. `E2E_BASE_URL` selects an existing frontend instead; set
`E2E_GATEWAY_URL` and `VITE_DEV_API_TARGET` if the Gateway URL differs. `E2E_GATEWAY_PORT`
changes the stack's published port. The JWT lifetime defaults to 30 seconds in this test
stack to exercise refresh behavior; production defaults are unchanged.

Tests create unique accounts under `@e2e.vmarket.test`, obtain actual OTP/reset codes from
Auth's `log` email provider, and exercise the browser against Gateway, Auth, User, Shop and
PostgreSQL. Role/shop/suspension/expiration fixtures run only in that disposable database.
Successful traffic is never replaced by mock responses. Controlled network interruption
is used for failure/retry cases, including a profile write whose response is lost after
the real backend commits it.

Coverage includes registration/verification/profile, pending accounts, duplicate email,
OTP errors/resend, five-attempt login lockout, reset/non-disclosure, administrative
suspension, profile field preservation/idempotency, repeated reload/two-tab refresh,
logout propagation, expired refresh, role guards, real shop states, and narrow layout/
keyboard operation. Unit tests cover additional deterministic refresh/error cases.

After tests, return to the repository root and stop the disposable stack:

```powershell
docker compose -p vmarket-auth-e2e -f frontend/e2e/compose.yml down -v
```

GitHub's frontend workflow runs lint, format, unit tests and build, then builds this
isolated stack and executes Chromium acceptance tests. Failure reports are available
as an Actions artifact. Provider-log tests validate authentication behavior; delivery to
an actual mailbox still requires Brevo and is not part of this ticket's acceptance.

## Role and shop boundaries

The account dropdown links to profile and seller area, plus Admin/Shipper areas when the
authenticated session includes the role. Direct URL access is guarded as well. No
client action grants a role. Pending accounts must verify email before entering those
areas. Default successful login returns home, or an authorized internal route requested
before sign-in; external return URLs are rejected.

`/seller` reads `/api/shops/me`. A genuine `SHOP_NOT_FOUND` opens the seller introduction;
service/network errors offer retry. Pending/rejected/suspended shops show their actual
state. Active shops still require `SELLER`; the frontend refreshes once for a recently
changed role and explains when approval has not yet produced access. Seller introduction
has no shop-registration form. Admin/Shipper pages and the active seller area are minimal
role destinations; their business dashboards remain separate tickets.

## Acceptance evidence — 13 October 2026

- Frontend: 66 unit/component tests passed; lint, format check and production build passed.
- Auth: 158 tests passed on Java 17, with zero skipped tests, including PostgreSQL
  Testcontainers, the legacy email payload and mixed username/email lockout.
- Gateway: 50 tests passed on Java 17, including 12 allowlist regression cases.
- Browser: all 19 Playwright cases passed against source-built Gateway/Auth/User/Shop,
  PostgreSQL 17 and RabbitMQ, including username/email login and shared lockout.
- Desktop 1280px and responsive 390px layouts rendered all assets without horizontal overflow.

Local Docker's Alpine runtime had an empty `/bin/busybox` after the disk-space failure.
Acceptance therefore ran the same freshly built service JARs in their Java 17 Maven build
stage, using an ignored local Compose override. Production Dockerfiles are unchanged;
CI builds and runs the normal runtime images. No Auth success response was mocked.

The existing Windows checkout had CRLF formatting failures. The frontend Git attribute now
enforces LF, and generated browser caches/reports are excluded from lint and formatting.

Screenshots use original Figma assets and the local Plus Jakarta Sans font. The forms add
visible labels and backend-required fields, while removing deferred Google/remember-me
controls. The profile screenshot uses an account in the disposable acceptance database.

| Screen          | Screenshot                                                                                  |
| --------------- | ------------------------------------------------------------------------------------------- |
| Login           | [Desktop](auth-ui/screenshots/login.png), [390px](auth-ui/screenshots/login-responsive.png) |
| Register        | [Desktop](auth-ui/screenshots/register.png)                                                 |
| Forgot password | [Desktop](auth-ui/screenshots/forgot-password.png)                                          |
| Reset password  | [Desktop](auth-ui/screenshots/reset-password.png)                                           |
| Profile         | [Real profile](auth-ui/screenshots/profile.png)                                             |
