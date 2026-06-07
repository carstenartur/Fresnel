# Fresnel

[![CI](https://github.com/carstenartur/Fresnel/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/carstenartur/Fresnel/actions/workflows/ci.yml)
[![Tests](https://github.com/carstenartur/Fresnel/actions/workflows/tests.yml/badge.svg?branch=main)](https://github.com/carstenartur/Fresnel/actions/workflows/tests.yml)
[![Coverage](https://github.com/carstenartur/Fresnel/actions/workflows/coverage.yml/badge.svg?branch=main)](https://carstenartur.github.io/Fresnel/coverage/)
[![CodeQL](https://github.com/carstenartur/Fresnel/actions/workflows/github-code-scanning/codeql/badge.svg?branch=main)](https://github.com/carstenartur/Fresnel/security/code-scanning)
[![License](https://img.shields.io/github/license/carstenartur/Fresnel)](LICENSE)
[![SBOM](https://img.shields.io/badge/SBOM-CycloneDX-informational?logo=owasp)](https://github.com/carstenartur/Fresnel/dependency-graph/sbom)
[![Release](https://img.shields.io/github/v/release/carstenartur/Fresnel?sort=semver)](https://github.com/carstenartur/Fresnel/releases/latest)
[![Docker](https://img.shields.io/badge/Docker-ghcr.io%2Fcarstenartur%2Ffresnel-blue?logo=docker)](https://github.com/carstenartur/Fresnel/pkgs/container/fresnel)
[![Maven Site](https://github.com/carstenartur/Fresnel/actions/workflows/site.yml/badge.svg?branch=main)](https://carstenartur.github.io/Fresnel/site/)
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

## Related Projects and References

| Project | Area |
|----------|------|
| https://en.wikipedia.org/wiki/Zone_plate | Fresnel zone plates and diffraction focusing |
| https://en.wikipedia.org/wiki/Diffractive_optics | Diffractive optics overview |
| https://en.wikipedia.org/wiki/Computer-generated_holography | Computer-generated holography |
| https://en.wikipedia.org/wiki/Gerchberg%E2%80%93Saxton_algorithm | Hologram synthesis algorithm |
| https://www.zemax.com | Commercial optical design software |
| https://www.synopsys.com/optical-solutions/codev.html | Professional optical simulation and design |

## Comparison

| Feature | Fresnel |
|----------|----------|
| Binary amplitude zone plates | ✓ |
| Greyscale phase masks | ✓ |
| RGB zone plates | ✓ |
| Multi-focus zone plates | ✓ |
| Hex macro cells | ✓ |
| Window foil generation | ✓ |
| Computer-generated holograms | ✓ |
| Spring Boot web application | ✓ |
| Open Source | ✓ |


## Installation

Fresnel ships in four flavours. Pick the one that matches your use-case;
all open the same UI at <http://localhost:8080>.
