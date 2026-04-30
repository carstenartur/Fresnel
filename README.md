# Fresnel

[![CI](https://github.com/carstenartur/Fresnel/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/carstenartur/Fresnel/actions/workflows/ci.yml)
[![Tests](https://github.com/carstenartur/Fresnel/actions/workflows/tests.yml/badge.svg?branch=main)](https://github.com/carstenartur/Fresnel/actions/workflows/tests.yml)
[![Coverage](https://github.com/carstenartur/Fresnel/actions/workflows/coverage.yml/badge.svg?branch=main)](https://carstenartur.github.io/Fresnel/coverage/)
[![Release](https://github.com/carstenartur/Fresnel/actions/workflows/deploy-release.yml/badge.svg)](https://github.com/carstenartur/Fresnel/releases)
[![Docker](https://img.shields.io/badge/docker-ghcr.io%2Fcarstenartur%2Ffresnel-blue)](https://github.com/carstenartur/Fresnel/pkgs/container/fresnel)
[![CodeQL](https://github.com/carstenartur/Fresnel/actions/workflows/github-code-scanning/codeql/badge.svg?branch=main)](https://github.com/carstenartur/Fresnel/security/code-scanning)
[![E2E](https://github.com/carstenartur/Fresnel/actions/workflows/e2e.yml/badge.svg?branch=main)](https://github.com/carstenartur/Fresnel/actions/workflows/e2e.yml)

📊 **[Coverage report](https://carstenartur.github.io/Fresnel/coverage/)** — JaCoCo HTML, regenerated on every push to `main` and daily.

A web application for designing printable diffractive optical elements:
Fresnel zone plates, hexagonal macro-cells, window-foil layouts and
simple computer-generated holograms.

## Quickstart — Run with Docker

```bash
docker run --rm -p 8080:8080 ghcr.io/carstenartur/fresnel:latest
# → open http://localhost:8080
```

No JDK or Node installation required — the image ships the full application
(Spring Boot API + React frontend) as a single self-contained artifact.

## Releases

Tagged releases are created automatically when a `v*.*.*` tag is pushed (or
manually via the *Release* workflow in GitHub Actions).  Each release publishes:

- **`backend-<version>.jar`** — executable Spring Boot fat jar (requires JDK 21)
- **SHA-256 checksum** for the jar
- **Docker image** `ghcr.io/carstenartur/fresnel:<version>` pushed to GHCR

Download the jar from the [Releases page](https://github.com/carstenartur/Fresnel/releases)
and run it directly if you already have a JDK:

```bash
java -jar backend-<version>.jar
# → http://localhost:8080
```

## Local Development

### Backend

```bash
# Default profile uses an in-memory H2 database
mvn -pl backend spring-boot:run
```

### Frontend (dev server with hot-reload)

```bash
cd frontend
npm install --legacy-peer-deps
npm run dev
# → http://localhost:5173  (Vite proxies /api → localhost:8080)
```

Vite proxies every `/api/*` request to `http://localhost:8080`, so you can
develop the frontend against a locally running backend without any CORS issues.

### Build the all-in-one Jar

```bash
mvn -B verify
# Resulting fat jar: backend/target/backend-*.jar
java -jar backend/target/backend-*.jar
```

### Build Docker image locally

```bash
docker build -t fresnel:dev .
docker run --rm -p 8080:8080 fresnel:dev
```

## Modules

- **`optics-core/`** – Pure Java library (no Spring dependency) with the
  optical math, validators and renderers. All numerical computations use
  IEEE‑754 `double` precision; see the note in `ZonePlateRenderer`.
- **`backend/`** – Spring Boot 4 REST API exposing `/api/designs/validate`,
  `/api/designs/preview.png` and `/api/designs/export.png`. Persists
  designs / render-job state via Spring Data JPA (H2 by default,
  PostgreSQL via the `postgres` profile) and protects mutating endpoints
  with HTTP Basic auth.  Also serves the bundled React frontend as static
  resources from `classpath:/static/`.
- **`frontend/`** – React + TypeScript + Vite single-page app: parameter
  panel, live validation, preview and PNG download.

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
