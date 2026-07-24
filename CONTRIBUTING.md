# Contributing to Fresnel

## Reproduce the pull-request gate locally

GitHub Actions runs exactly this command for every pull request:

```bash
mvn -B -ntp -Dfresnel.e2e.skip=false verify
```

Run it from the repository root with JDK 21 and Maven installed. Maven owns the
complete build lifecycle:

- Java compilation and unit/integration tests for every reactor module;
- deterministic documentation-manifest verification;
- supply-chain and release-workflow invariants;
- JaCoCo instruction-coverage checks;
- installation of the pinned Node/npm toolchain and the frontend production build;
- Chromium installation, Spring Boot start/stop and the Playwright E2E suite.

The browser suite is intentionally opt-in for a faster edit/build loop:

```bash
mvn -B -ntp verify
```

A CI failure must be reproducible with one of those Maven commands. Workflow-only
scripts must not introduce an additional pull-request gate. GitHub-specific
workflows are reserved for report publication, release orchestration and
platform-specific installers after changes reach `main` or an immutable release
tag.

## Frontend development

For the Vite development server, with the backend already running:

```bash
cd frontend
npm ci --legacy-peer-deps
npm run dev
```

A direct Playwright run remains available for focused debugging after starting
the backend locally:

```bash
cd frontend
npm run e2e
```
