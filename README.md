# Fresnel

[![CI](https://github.com/carstenartur/Fresnel/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/carstenartur/Fresnel/actions/workflows/ci.yml)
[![Tests](https://github.com/carstenartur/Fresnel/actions/workflows/tests.yml/badge.svg?branch=main)](https://github.com/carstenartur/Fresnel/actions/workflows/tests.yml)
[![Coverage](https://github.com/carstenartur/Fresnel/actions/workflows/coverage.yml/badge.svg?branch=main)](https://carstenartur.github.io/Fresnel/coverage/)
[![Maven Site](https://github.com/carstenartur/Fresnel/actions/workflows/site.yml/badge.svg?branch=main)](https://carstenartur.github.io/Fresnel/site/)
[![Release](https://github.com/carstenartur/Fresnel/actions/workflows/deploy-release.yml/badge.svg)](https://github.com/carstenartur/Fresnel/releases)
[![Docker](https://img.shields.io/badge/docker-ghcr.io%2Fcarstenartur%2Ffresnel-blue)](https://github.com/carstenartur/Fresnel/pkgs/container/fresnel)
[![CodeQL](https://github.com/carstenartur/Fresnel/actions/workflows/github-code-scanning/codeql/badge.svg?branch=main)](https://github.com/carstenartur/Fresnel/security/code-scanning)
[![E2E](https://github.com/carstenartur/Fresnel/actions/workflows/e2e.yml/badge.svg?branch=main)](https://github.com/carstenartur/Fresnel/actions/workflows/e2e.yml)

📊 **[Coverage report](https://carstenartur.github.io/Fresnel/coverage/)** — JaCoCo HTML, regenerated on every push to `main` and daily.

## Test Reports

| Where | What |
|-------|------|
| **Actions Job Summary** | After every *Tests* workflow run the [dorny/test-reporter](https://github.com/dorny/test-reporter) step renders a JUnit summary (list of all tests with ✔/✘) directly in the GitHub Actions UI. |
| **Surefire Artifacts** | The *Tests* workflow uploads `surefire-reports` as a workflow artifact (14 days retention). Download it from the [Actions run page](https://github.com/carstenartur/Fresnel/actions/workflows/tests.yml) → *Artifacts*. |
| **Maven Site** | The *Maven Site* workflow generates Surefire HTML reports, JaCoCo coverage and project info for every module. The live site is published to **[carstenartur.github.io/Fresnel/site/](https://carstenartur.github.io/Fresnel/site/)** after every push to `main`. The `maven-site` artifact is also available for download from the [site workflow runs](https://github.com/carstenartur/Fresnel/actions/workflows/site.yml). |

A web application for designing printable diffractive optical elements:
Fresnel zone plates, hexagonal macro-cells, window-foil layouts and
simple computer-generated holograms.

## Installation

Fresnel ships in four flavours. Pick the one that matches your use-case;
all open the same UI at <http://localhost:8080>.

| Flavour | Best for | Java required? |
|---|---|---|
| [Docker](#docker) | Server / container deployments | No |
| [Windows installer](#windows-installer) | Local desktop use on Windows | No (bundled) |
| [Linux installer](#linux-installer) | Local desktop use on Linux | No (bundled) |
| [Plain JAR](#plain-jar) | Anywhere you already have JDK 21 (CI etc) | Yes |

> **Docker is best for server / container deployments. The Windows and
> Linux installers are best for local desktop use** — they bundle a
> private Java runtime, register a shortcut, and store data outside the
> install directory.

See [`packaging/README-install.md`](packaging/README-install.md) for the
full installation guide, including how to change the port, where
configuration lives, and how to reset local data.

### Docker

```bash
docker run --rm -p 8080:8080 ghcr.io/carstenartur/fresnel:latest
# → open http://localhost:8080
```

No JDK or Node installation required — the image ships the full
application (Spring Boot API + React frontend) as a single
self-contained artifact. The default container uses an in-memory H2
database; mount a volume and switch to the standalone profile to
persist data:

```bash
docker run --rm -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=standalone \
  -e FRESNEL_DATA_DIR=/data \
  -v fresnel-data:/data \
  ghcr.io/carstenartur/fresnel:latest
```

### Windows installer

Download the latest `Fresnel-<version>.msi` (or the
`fresnel-<version>-windows.zip` fallback) from the
[Releases page](https://github.com/carstenartur/Fresnel/releases) and
run it. The installer:

- installs Fresnel under `C:\Program Files\Fresnel\`
- registers a Start-menu shortcut
- bundles its own Java 21 runtime (no JDK needed)
- stores the database under `%APPDATA%\Fresnel\` — **never** inside the
  install directory

Then open <http://localhost:8080>.

### Linux installer

Download `fresnel_<version>_amd64.deb` (Debian/Ubuntu) or the
`fresnel-<version>-linux.tar.gz` fallback from the
[Releases page](https://github.com/carstenartur/Fresnel/releases):

```bash
sudo apt install ./fresnel_<version>_amd64.deb
fresnel                              # or: /opt/fresnel/bin/Fresnel
# → http://localhost:8080
```

The `.deb` bundles a private JRE and stores data under
`$HOME/.local/share/fresnel/`. The tar.gz fallback uses your system
Java; unpack it and run `bin/start-fresnel.sh`.

### Plain JAR

Each release publishes `backend-<version>.jar` plus a SHA-256 checksum.

```bash
java -jar backend-<version>.jar                                # in-memory DB
java -Dspring.profiles.active=standalone -jar backend-*.jar    # file-based DB under ~/.fresnel/
```

The standalone profile honours `FRESNEL_DATA_DIR`, `SERVER_PORT`, and
the `FRESNEL_SECURITY_*` overrides documented below.

### Where data and configuration live

| Item | Windows | Linux | macOS |
|---|---|---|---|
| Database | `%APPDATA%\Fresnel\db\` | `$HOME/.local/share/fresnel/db/` | `$HOME/Library/Application Support/Fresnel/db/` |
| Config | `<install>\config\application-standalone.properties` | `<install>/config/application-standalone.properties` | same |

Change the port: edit `server.port` in `application-standalone.properties`,
or set `SERVER_PORT=9090` before launching. Reset local data: stop
Fresnel and delete the database directory above.

## Releases

Releases are created manually via the *Release* workflow in GitHub Actions
(`workflow_dispatch`, input `release_version`, no `v` prefix). The
*Release Packages* workflow then builds the cross-platform artifacts.
Each release publishes:

- **`backend-<version>.jar`** — executable Spring Boot fat jar (requires JDK 21)
- **`fresnel-<version>-windows.zip`** — portable Windows distribution (system Java)
- **`fresnel-<version>-linux.tar.gz`** — portable Linux distribution (system Java)
- **`Fresnel-<version>.msi`** — Windows installer with bundled JRE (jpackage)
- **`fresnel_<version>_amd64.deb`** — Debian/Ubuntu installer with bundled JRE (jpackage)
- **SHA-256 checksums** for every archive
- **Docker image** `ghcr.io/carstenartur/fresnel:<version>` pushed to GHCR

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

## Plugins

Fresnel supports six diffractive element types, each an independent plugin.
See **[docs/index.md](docs/index.md)** for the full plugin overview including
example images and API references.

| Plugin | Description |
|--------|-------------|
| [Zone Plate](docs/plugins/zone-plate.md) | Single Fresnel zone plate — binary amplitude or greyscale phase |
| [RGB Zone Plate](docs/plugins/rgb-zone-plate.md) | Zone plate rendered at R / G / B wavelengths and composited |
| [Multi-Focus](docs/plugins/multi-focus.md) | Aperture split among multiple focal targets |
| [Hex Macro Cell](docs/plugins/hex-macro-cell.md) | Hexagonal array of sub-zone-plates |
| [Window Foil](docs/plugins/window-foil.md) | Rectangular sheet tiled with hex macro cells |
| [Hologram](docs/plugins/hologram.md) | Computer-generated hologram (Gerchberg–Saxton) |

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
