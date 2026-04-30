# Fresnel

A web application for designing printable diffractive optical elements:
Fresnel zone plates, hexagonal macro-cells, window-foil layouts and
simple computer-generated holograms.

## Modules

- **`optics-core/`** – Pure Java library (no Spring dependency) with the
  optical math, validators and renderers. All numerical computations use
  IEEE‑754 `double` precision; see the note in `ZonePlateRenderer`.
- **`backend/`** – Spring Boot 3 REST API exposing `/api/designs/validate`,
  `/api/designs/preview.png` and `/api/designs/export.png`.
- **`frontend/`** – React + TypeScript + Vite single-page app: parameter
  panel, live validation, preview and PNG download.

## Build & test

```bash
# Java side
mvn -B test                  # runs all backend + optics-core tests
mvn -B install -DskipTests   # builds the runnable jar

# Frontend
cd frontend
npm install
npm run build                # type-checks and bundles to frontend/dist
npm run dev                  # dev server on http://localhost:5173 (proxies /api)
```

## Run locally

```bash
# Terminal 1 – backend
mvn -pl backend spring-boot:run

# Terminal 2 – frontend dev server
cd frontend && npm run dev
# open http://localhost:5173
```
