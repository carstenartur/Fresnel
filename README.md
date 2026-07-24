# Fresnel

[![CI](https://github.com/carstenartur/Fresnel/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/carstenartur/Fresnel/actions/workflows/ci.yml)
[![Tests](https://img.shields.io/endpoint?url=https://carstenartur.github.io/Fresnel/site/badges/tests.json)](https://carstenartur.github.io/Fresnel/site/tests/)
[![Coverage](https://img.shields.io/endpoint?url=https://carstenartur.github.io/Fresnel/site/badges/coverage.json)](https://carstenartur.github.io/Fresnel/site/coverage/)
[![CodeQL](https://github.com/carstenartur/Fresnel/actions/workflows/github-code-scanning/codeql/badge.svg?branch=main)](https://github.com/carstenartur/Fresnel/security/code-scanning)
[![E2E](https://github.com/carstenartur/Fresnel/actions/workflows/e2e.yml/badge.svg?branch=main)](https://github.com/carstenartur/Fresnel/actions/workflows/e2e.yml)
[![Maven Site](https://github.com/carstenartur/Fresnel/actions/workflows/site.yml/badge.svg?branch=main)](https://carstenartur.github.io/Fresnel/site/)
[![License](https://img.shields.io/github/license/carstenartur/Fresnel)](LICENSE)
[![SBOM](https://img.shields.io/badge/SBOM-CycloneDX-informational?logo=owasp)](https://github.com/carstenartur/Fresnel/dependency-graph/sbom)
[![Release](https://img.shields.io/github/v/release/carstenartur/Fresnel?sort=semver)](https://github.com/carstenartur/Fresnel/releases/latest)
[![Docker](https://img.shields.io/badge/Docker-ghcr.io%2Fcarstenartur%2Ffresnel-blue?logo=docker)](https://github.com/carstenartur/Fresnel/pkgs/container/fresnel)
[![DOI](https://zenodo.org/badge/1224875238.svg)](https://doi.org/10.5281/zenodo.20838658)

Fresnel is an open platform for **computational diffractive optics**. It combines
optical models, deterministic renderers, validation, manufacturing exports,
reproducible `.fresnel` jobs and a React/Spring Boot application.

## Capabilities

- binary-amplitude and greyscale-phase Fresnel zone plates;
- RGB and multi-focus diffractive elements;
- hexagonal macro cells and printable window foils;
- Gerchberg–Saxton computer-generated holograms;
- orientation-specific variable-line printer calibration gratings;
- optical propagation and quality analysis;
- PNG, SVG, PDF, DXF, Gerber, STL and trusted-profile PCL production paths;
- versioned plugin schemas and deterministic `.fresnel` job execution.

## Project structure

- **`optics-core/`** — pure Java optical models, renderers, validators and
  production exporters.
- **`backend/`** — Spring Boot REST API, job execution, persistence, security,
  desktop integration and bundled SPA hosting.
- **`frontend/`** — React, TypeScript and Vite single-page application.
- **`docs/`** — plugin contracts, experiment procedures, QA assessments and
  deployment guidance.
- **`packaging/`** — release archives, native installers and verification tools.

## Installation

Fresnel supports four operating modes:

| Mode | Intended use | Java required |
|---|---|---:|
| Docker | controlled server/container deployment | no |
| Windows installer | local desktop use | no |
| Linux installer | local desktop use | no |
| executable JAR | development, CI or managed servers | JDK/JRE 21 |

The native installers and default JAR profile are **loopback-only**. Network
profiles fail closed unless explicit non-default credentials are supplied.

### Docker

The container activates the strict `container` profile. Both passwords are
mandatory, must contain at least 12 characters, must differ and may not use a
published default.

```bash
export FRESNEL_SECURITY_USER_USERNAME=alice
export FRESNEL_SECURITY_USER_PASSWORD='correct-horse-battery-staple'
export FRESNEL_SECURITY_ADMIN_USERNAME=fresnel-admin
export FRESNEL_SECURITY_ADMIN_PASSWORD='violet-meteor-archive-2026'

docker run --rm -p 127.0.0.1:8080:8080 \
  -e FRESNEL_SECURITY_USER_USERNAME \
  -e FRESNEL_SECURITY_USER_PASSWORD \
  -e FRESNEL_SECURITY_ADMIN_USERNAME \
  -e FRESNEL_SECURITY_ADMIN_PASSWORD \
  ghcr.io/carstenartur/fresnel:latest
```

For persistent H2 storage, compose `standalone` with the final strict
`container` profile:

```bash
docker run --rm -p 127.0.0.1:8080:8080 \
  -e SPRING_PROFILES_ACTIVE=standalone,container \
  -e FRESNEL_DATA_DIR=/data \
  -e FRESNEL_SECURITY_USER_USERNAME \
  -e FRESNEL_SECURITY_USER_PASSWORD \
  -e FRESNEL_SECURITY_ADMIN_USERNAME \
  -e FRESNEL_SECURITY_ADMIN_PASSWORD \
  -v fresnel-data:/data \
  ghcr.io/carstenartur/fresnel:latest
```

See [Secure deployment](docs/security/deployment.md) before binding Fresnel to a
LAN or public interface. **HTTP Basic must be protected by TLS off loopback.**

### Windows and Linux installers

Download the current `.msi`, `.deb` or portable archive from the
[Releases page](https://github.com/carstenartur/Fresnel/releases). Native
installers bundle Java 21, register `.fresnel` jobs and store user data outside
the installation directory.

Typical data locations:

| Platform | Data directory |
|---|---|
| Windows | `%APPDATA%\Fresnel\` |
| Linux | `$HOME/.local/share/Fresnel/` |
| macOS / plain JAR | configurable with `FRESNEL_DATA_DIR` |

The desktop profile binds to `127.0.0.1` and is intended for one local user.
Full platform instructions are in
[`packaging/README-install.md`](packaging/README-install.md).

### Executable JAR

```bash
# Local in-memory database; loopback only
java -jar backend-<version>.jar

# Local persistent H2 database; loopback only
java -Dspring.profiles.active=standalone -jar backend-<version>.jar
```

For PostgreSQL, set all database and application secrets. There are no
production password fallbacks:

```bash
export SPRING_PROFILES_ACTIVE=postgres
export DB_URL='jdbc:postgresql://db.example.internal:5432/fresnel'
export DB_USER='fresnel_app'
export DB_PASSWORD='database-specific-secret'
export FRESNEL_SECURITY_USER_USERNAME=alice
export FRESNEL_SECURITY_USER_PASSWORD='correct-horse-battery-staple'
export FRESNEL_SECURITY_ADMIN_USERNAME=fresnel-admin
export FRESNEL_SECURITY_ADMIN_PASSWORD='violet-meteor-archive-2026'
java -jar backend-<version>.jar
```

## Authentication and authorization

Fresnel uses stateless HTTP Basic authentication.

Public analytical endpoints include validation, bounded previews and design
recommendations. Mutating operations, manufacturing exports and all render-job
lifecycle endpoints require authentication.

Local development seeds these loopback-only accounts:

| Username | Password | Roles |
|---|---|---|
| `user` | `user` | `USER` |
| `admin` | `admin` | `USER`, `ADMIN` |

Do not expose the local profile to a network. The `container` and `postgres`
profiles reject these defaults.

Saved designs and render jobs are owner-scoped. Render-job identifiers contain
192 bits of random entropy, but remain private identifiers rather than public
share links. Only the owner or an administrator may read status, progress events
or results. Unknown and unauthorized identifiers both return `404`.

## Local development

Requirements: JDK 21, Maven and Node.js 20.

```bash
# Backend, in-memory H2, loopback only
mvn -pl backend spring-boot:run
```

```bash
# Frontend development server
cd frontend
npm ci --legacy-peer-deps
npm run dev
```

Vite serves the UI at `http://localhost:5173` and proxies `/api` to the backend
at `http://localhost:8080`.

Build the complete application:

```bash
mvn -B verify
java -jar backend/target/backend-*.jar
```

Build a local container:

```bash
docker build -t fresnel:dev .
docker run --rm -p 127.0.0.1:8080:8080 \
  -e FRESNEL_SECURITY_USER_PASSWORD='correct-horse-battery-staple' \
  -e FRESNEL_SECURITY_ADMIN_PASSWORD='violet-meteor-archive-2026' \
  fresnel:dev
```

## Tests and quality reports

```bash
mvn -B test
mvn -B verify
cd frontend && npm run build
cd frontend && npm run e2e
```

- [Published test summary](https://carstenartur.github.io/Fresnel/site/tests/)
- [Published coverage summary](https://carstenartur.github.io/Fresnel/site/coverage/)
- [Maven Site](https://carstenartur.github.io/Fresnel/site/)
- [Professional QA assessment](docs/qa/2026-07-24-professional-qa.md)

The Maven Site generation path runs as a read-only pull-request gate. Only the
validated artifact is published from `main`.

## Plugins

| Plugin | Description |
|---|---|
| [Zone Plate](docs/plugins/zone-plate.md) | single binary or greyscale zone plate |
| [RGB Zone Plate](docs/plugins/rgb-zone-plate.md) | multi-wavelength composite |
| [Multi-Focus](docs/plugins/multi-focus.md) | multiple focal targets |
| [Hex Macro Cell](docs/plugins/hex-macro-cell.md) | hexagonal sub-element array |
| [Window Foil](docs/plugins/window-foil.md) | printable tiled sheet |
| [Hologram](docs/plugins/hologram.md) | computer-generated hologram |
| [Variable Line Grating](docs/plugins/variable-line-grating.md) | orientation-specific printer calibration |

## Documentation

- [Documentation index](docs/index.md)
- [Experiments handbook](docs/experiments/first-zone-plate.md)
- [Plugin schema architecture](docs/plugin-schemas.md)
- [Secure deployment](docs/security/deployment.md)
- [Installation guide](packaging/README-install.md)

## Releases

The release workflow validates version metadata, builds and tests the Java and
frontend applications, publishes checksummed JAR/archive assets and builds the
container image. Native Windows and Linux packages are generated on matching
runners.

Each release can include:

- executable Spring Boot JAR;
- Windows and Linux portable archives;
- Windows MSI and Debian package with bundled Java runtime;
- SHA-256 checksums;
- versioned multi-architecture container image.

## License

See [LICENSE](LICENSE).
