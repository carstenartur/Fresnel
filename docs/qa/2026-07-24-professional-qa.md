# Professional QA and Release-Readiness Assessment

**Project:** Fresnel  
**Assessment date:** 2026-07-24  
**Assessed baseline:** `main` at `5516fef21b9c5f3d3e71a0979f949a6ebccf59dd`  
**Diagnostic / remediation PR:** #97 (`qa/professional-release-gate`)  
**Assessment type:** source, automated-test, CI/CD, authorization, security-configuration, packaging and release-process review

## Executive decision

| Target | Decision | Rationale |
|---|---|---|
| Local development | **GO with observations** | Java, frontend, test, database migration and Docker build paths are operational. |
| Loopback-only standalone desktop use | **GO with observations** | The standalone profile binds to `127.0.0.1`; default credentials remain undesirable but exposure is intentionally local. |
| Shared LAN, Internet-facing Docker or PostgreSQL deployment | **NO-GO** | Network profiles can inherit public default credentials, and render-job reads do not enforce stored ownership. |
| Release from `main` | **NO-GO until QA-001, QA-002 and QA-008 are closed** | The publication path was not a pre-merge gate, the production credential contract is not fail-safe, and render-job data is vulnerable to cross-user disclosure. |

The codebase has a strong automated baseline, but a green pull request did not represent every operation performed after merge. The assessment therefore distinguishes **product correctness** from **release-system correctness** and from **multi-user authorization safety**.

## Evidence summary

- **364 JUnit tests passed**, with zero failures, errors or skipped tests across 68 Surefire suites.
- Pull-request runs for **CI, Tests, Coverage and E2E passed** on the QA branch.
- A read-only diagnostic Maven Site build passed; PR #97 now moves the complete generation/staging path into the production workflow's pull-request gate.
- JaCoCo instruction coverage calculated from the workflow artifact:
  - `optics-core`: **79.82%** (`14,159 / 17,739` instructions)
  - `backend`: **76.17%** (`15,236 / 20,002` instructions)
  - total: **77.89%** (`29,395 / 37,741` instructions)
- The Docker image builds and uses a non-root runtime user.
- Chromium Playwright E2E tests pass against the packaged Spring Boot backend and React frontend.

## Findings

### QA-001 — HIGH — Network-exposed deployments inherit public default credentials

**Observed**

The common application configuration provides `user/user` and `admin/admin`. `SecurityConfig` also contains the same fallback values. The PostgreSQL profile configures the database but does not replace the application credentials. The Docker image exposes port 8080 and starts the common profile unless the operator supplies another profile or overrides.

**Impact**

Mutating and export endpoints require authentication, but the credentials are published in source and documentation. A container bound to a LAN or public interface can therefore expose authenticated functions under known credentials. HTTP Basic also requires TLS at the deployment boundary to avoid credential disclosure in transit.

**Required remediation**

1. Add a container/production security profile that has **no password defaults** and fails startup when either password is missing.
2. Make the PostgreSQL profile require the same secrets.
3. Keep the loopback-only standalone profile explicit and document its trust boundary.
4. Add an integration test proving production/container startup fails without credentials and succeeds with supplied secrets.
5. Document TLS/reverse-proxy requirements for any non-loopback deployment.

**Exit criterion:** no network-exposed supported profile can start with known credentials.

Tracking issue: #98.

### QA-002 — HIGH — A post-merge-only workflow can turn `main` red

**Observed**

The Maven Site workflow was triggered only by pushes to `main`; it was absent from the pull-request gate. PR #96 was green, then the merge commit produced a red default branch. The complete Maven build and report generation pass on the QA branch, which narrows the original failure to the publication-only portion or its environment rather than application tests.

**Remediation implemented in PR #97**

- The production `Maven Site` workflow now runs its build job on pull requests with read-only permissions.
- Maven output staging, badge generation, test-report validation and coverage validation are extracted into `packaging/stage-maven-site.py` and shared by pull-request and main runs.
- The staging step verifies both module sites, JaCoCo CSV reports and Surefire XML reports and writes `site/qa-summary.json`.
- An initial aggregate instruction-coverage floor of **75%** is enforced in this release/site gate.
- Publication is a separate main-only job; only that job receives `contents: write`.
- The deploy job consumes the exact artifact validated by the build job.

**Exit criterion:** the refactored Maven Site check passes in PR #97 and the next `main` publication completes successfully.

### QA-003 — MEDIUM — Coverage has critical blind spots

**Observed**

Overall instruction coverage is 77.89%. PR #97 introduces a 75% aggregate floor in the site/release gate, but the dedicated coverage workflow is still report-only and a global percentage can conceal weak critical paths. Notable low-coverage production paths in the assessed artifact include:

| Class/path | Approx. instruction coverage | Risk |
|---|---:|---|
| `VariableLineGratingController` | **1.46%** | HTTP contract, authorization and error mapping are weakly exercised. |
| `VariableLineGratingRasterizer` | **22.98%** | Annotation and edge-layout behavior can regress despite model tests. |
| `RasterText5x7` | **0%** | Native raster/PCL labels have no direct glyph/bounds tests. |
| `FresnelDesktopLauncher` | **0%** | Desktop startup/integration path is not covered by the Java suite. |
| `FresnelDocsCli` | **0%** | Documentation-generation CLI behavior is not directly covered. |

**Required remediation**

- Add controller integration tests for preview, PNG, SVG, PDF, PCL, invalid profile, invalid compression, resource bounds and authentication.
- Add direct raster annotation tests for all supported glyphs, rotations and boundary clipping.
- Apply the coverage floor to the authoritative required check and raise it deliberately over time.
- Add package/class floors for security-sensitive and production-export paths rather than relying only on a global average.

**Exit criterion:** coverage regression fails CI and the new plugin's controller/raster paths have meaningful positive, negative and boundary tests.

Tracking issue: #99.

### QA-004 — MEDIUM — Release automation can skip tests and update `main` directly

**Observed**

The manual release workflow exposes a `skip_tests` input and, for a live release, advances `main` through the GitHub API before creating the tag and release. It requests broad repository, issue, pull-request and package permissions at workflow scope.

**Impact**

A production release can bypass the ordinary test expectation and normal pull-request review trail. A partial failure after advancing `main` can leave repository version state changed without completing all publication steps.

**Required remediation**

- Disallow `skip_tests` for non-dry-run releases.
- Use a protected release environment with approval.
- Narrow permissions per job.
- Verify the expected starting SHA before changing `main`.
- Prefer a reviewed release PR or an atomic/tag-first process with explicit recovery steps.
- Generate provenance/attestations for released artifacts and container images.

Tracking issue: #100.

### QA-005 — MEDIUM — CI/CD dependencies are mutable references

**Observed**

Workflows reference actions by mutable major tags, and Docker stages reference mutable image tags rather than immutable digests.

**Impact**

Upstream tag movement can change trusted build or release code without a repository diff. This is particularly important for workflows with write/package permissions.

**Required remediation**

Pin third-party and GitHub-maintained actions to reviewed full commit SHAs, retain the semantic version in comments, and update them through Dependabot/Renovate. Pin release and runtime container base images by digest.

Tracking issue: #100.

### QA-006 — LOW — CI duplication increases cost and diagnostic noise

`mvn test`/`verify` is repeated across CI, Tests, Coverage and site generation. The redundancy adds defense in depth but also increases elapsed time and makes it harder to identify the authoritative gate.

Consolidate build outputs where safe, clearly designate required checks, and keep specialized workflows focused on their unique assertions (documentation manifest, coverage threshold, E2E, packaging and publication).

### QA-007 — LOW — Documentation metadata has minor drift

The README contains a duplicate top-level `# Fresnel` heading, and the parent POM description lists older element categories without the variable-line grating. These do not affect runtime behavior but reduce release polish and metadata accuracy.

### QA-008 — HIGH — Predictable public render-job IDs bypass stored ownership

**Observed**

Render jobs persist an `ownerId`, but retrieval does not enforce it. IDs are generated from the current timestamp plus a process-local sequence (`j-<milliseconds>-<sequence>`). The security configuration permits anonymous GET access to job status, SSE events and PNG results. `RenderJobService.get()` and `resultPng()` query by ID only, while status responses can include labels and raw error messages.

**Impact**

In a multi-user or network-exposed deployment, another caller can enumerate or predict identifiers and retrieve another user's status, error details or generated image. The stored ownership field creates an expectation of isolation that is not met. This is an insecure direct object reference / broken object-level authorization condition.

**Required remediation**

- Replace time/sequence IDs with identifiers containing at least 128 bits of unpredictable entropy.
- Require authentication for primary job status, SSE and result routes and enforce owner-or-admin access.
- If sharing is required, introduce a separate high-entropy, revocable share token.
- Return the same external response for unauthorized and unknown IDs.
- Do not expose raw internal exception text to untrusted callers.
- Cover live and database-rehydrated jobs with Alice/Bob/anonymous/admin integration tests.

**Exit criterion:** no caller can access another user's job without an explicit authorization or sharing grant.

Tracking issue: #101.

## Positive controls observed

- Java 21 and TypeScript strict compilation are used.
- The Docker runtime executes as a dedicated non-root user.
- Database schema changes are managed through Flyway; JPA schema auto-generation is disabled.
- The standalone profile binds to loopback by default.
- CORS uses an explicit origin allowlist.
- The REST API uses stateless authentication, and most mutating/export operations are protected.
- Test, coverage, E2E, Docker build, documentation-manifest and release-package workflows exist.
- The variable-line grating implementation includes bounded parameters, trusted printer profiles and deterministic output tests.

## Recommended required checks for `main`

1. `CI / Build & test (JDK 21 + Node 20)`
2. `CI / Docker build`
3. `Tests / JUnit tests`
4. `Coverage / JaCoCo coverage` with an enforced floor
5. `E2E / Playwright E2E (chromium)`
6. `Maven Site / Build Maven Site` as a read-only PR job
7. CodeQL and dependency-review checks
8. A release-package smoke test for changes touching packaging, launchers or the release workflow

## Closure priority

1. **Immediate:** merge the refactored pre-merge Maven Site gate after review and observe a successful publication from `main`.
2. **Before any shared/network deployment:** close #98 and #101 (credentials and render-job object authorization).
3. **Before the next feature release:** close #99 (targeted tests and authoritative coverage enforcement).
4. **Next hardening cycle:** close #100 (release permissions/process and immutable workflow/container dependencies).

## Assessment limitations

This assessment validates source and CI evidence and performs static configuration review. It does not claim physical optical accuracy, printer fidelity, a complete penetration test, browser compatibility beyond Chromium, PostgreSQL behavior under production load, or installer behavior on every supported operating system. Those require dedicated physical, security, performance and platform test plans.
