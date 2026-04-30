# Fresnel

A web application for designing printable diffractive optical elements:
Fresnel zone plates, hexagonal macro-cells, window-foil layouts and
simple computer-generated holograms.

## Modules

- **`optics-core/`** – Pure Java library (no Spring dependency) with the
  optical math, validators and renderers. All numerical computations use
  IEEE‑754 `double` precision; see the note in `ZonePlateRenderer`.
- **`backend/`** – Spring Boot 4 REST API exposing `/api/designs/validate`,
  `/api/designs/preview.png` and `/api/designs/export.png`. Persists
  designs / render-job state via Spring Data JPA (H2 by default,
  PostgreSQL via the `postgres` profile) and protects mutating endpoints
  with HTTP Basic auth.
- **`frontend/`** – React + TypeScript + Vite single-page app: parameter
  panel, live validation, preview and PNG download.

## Build & test

```bash
# Java side
mvn -B test                  # runs all backend + optics-core tests (requires JDK 21)
mvn -B install -DskipTests   # builds the runnable jar

# Frontend
cd frontend
npm install --legacy-peer-deps
npm run build                # type-checks and bundles to frontend/dist
npm run dev                  # dev server on http://localhost:5173 (proxies /api)
```

## Run locally

```bash
# Terminal 1 – backend (default profile uses an in-memory H2 database)
mvn -pl backend spring-boot:run

# Terminal 2 – frontend dev server
cd frontend && npm run dev
# open http://localhost:5173
```

## Authentication

Mutating endpoints (`POST /api/designs/save`, `POST /api/jobs/**`,
`POST /api/holograms/**`, etc.) require HTTP Basic auth. Read-only
endpoints (`/api/designs/validate`, `/api/designs/preview*`) remain
publicly accessible.

Two users are seeded in memory by default:

| Username | Password | Roles         |
|----------|----------|---------------|
| `user`   | `user`   | `USER`        |
| `admin`  | `admin`  | `USER, ADMIN` |

Override the credentials for any non-throwaway environment by setting:

```bash
export FRESNEL_SECURITY_USER_USERNAME=alice
export FRESNEL_SECURITY_USER_PASSWORD='strong-password-here'
export FRESNEL_SECURITY_ADMIN_USERNAME=root
export FRESNEL_SECURITY_ADMIN_PASSWORD='another-strong-password'
```

Saved designs and submitted render jobs are scoped to the authenticated
user (`owner_id` column); the admin role sees all rows in listings.

## PostgreSQL persistence

The `postgres` Spring profile swaps H2 for PostgreSQL. Bring up a
container and start the backend pointed at it:

```bash
# 1. Start PostgreSQL
docker run --rm -d --name fresnel-pg \
  -e POSTGRES_USER=fresnel \
  -e POSTGRES_PASSWORD=fresnel \
  -e POSTGRES_DB=fresnel \
  -p 5432:5432 postgres:16

# 2. Start backend with the postgres profile
export DB_URL=jdbc:postgresql://localhost:5432/fresnel
export DB_USER=fresnel
export DB_PASSWORD=fresnel
mvn -pl backend spring-boot:run -Dspring-boot.run.profiles=postgres
```

Flyway runs the migrations under
`backend/src/main/resources/db/migration/` on startup; the same script
is applied to both H2 (in PostgreSQL compatibility mode) and PostgreSQL.

## End-to-end tests

Playwright specs live in `frontend/e2e/`. They drive the SPA against a
running backend (default: `http://localhost:8080`, basic-auth `user/user`).

```bash
cd frontend
npm install --legacy-peer-deps
npx playwright install --with-deps chromium    # one-off
# Start backend in another shell, then:
npm run e2e
```

CI runs them automatically via `.github/workflows/e2e.yml` on every push.
