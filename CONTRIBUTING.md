# Contributing to Fresnel

## Reproduce the pull-request gate locally

GitHub Actions runs exactly this command for every pull request:

```bash
mvn -B -ntp -Dfresnel.e2e.skip=false verify
```

Run it from the repository root with JDK 21 and Maven installed. Maven owns the
complete build lifecycle in a diagnostic order:

1. validate coupled frontend dependency versions against `package.json` and
   `package-lock.json`;
2. run `npm ci` without peer-dependency bypasses and verify the installed tree
   with `npm ls --all`;
3. run the dependency-contract unit tests, TypeScript compilation and the Vite
   production build;
4. run Java compilation, JUnit unit/integration tests, documentation and
   supply-chain invariants, and JaCoCo coverage checks;
5. copy the production frontend into Spring Boot and prove that the packaged
   HTML and hashed assets are served by the Maven-started application;
6. execute Playwright against that packaged application, without retries, and
   write per-test JUnit XML to `frontend/test-results/playwright-junit.xml`.

The browser suite is intentionally opt-in for a faster edit/build loop:

```bash
mvn -B -ntp verify
```

A CI failure must be reproducible with one of those Maven commands. Workflow-only
scripts must not introduce an additional pull-request gate. GitHub-specific
workflows are reserved for report publication, release orchestration and
platform-specific installers after changes reach `main` or an immutable release
tag.

## Interpreting dependency-update failures

The first failing stage identifies the violated contract:

- `verify-frontend-dependency-contracts`: the manifest and lockfile disagree, or
  React, React DOM and their type packages were not updated atomically;
- `npm-ci`: npm rejected the dependency graph or lockfile;
- `verify-installed-frontend-dependency-tree`: installed peer dependencies are
  invalid;
- `test-frontend-dependency-contracts`: the compatibility verifier itself is
  broken;
- `npm-build`: TypeScript compilation or the production bundle is incompatible;
- `packagedFrontendIsServedByTheMavenStartedBackend`: packaging or application
  startup is broken;
- `completePlaywrightSuitePassesAgainstThePackagedApplication`: a named browser
  behavior failed; inspect the Playwright log and per-test JUnit report.

Never work around an update failure with `--legacy-peer-deps`. Packages that form
one compatibility unit must be updated together instead.

## Frontend development

For the Vite development server, with the backend already running:

```bash
cd frontend
npm run verify:dependency-contracts
npm ci
npm ls --all
npm run test:contracts
npm run dev
```

A direct Playwright run remains available for focused debugging after starting
the backend locally. It uses Vite for the fast development loop; the Maven gate
uses the packaged Spring Boot application instead.

```bash
cd frontend
npm run e2e
```
