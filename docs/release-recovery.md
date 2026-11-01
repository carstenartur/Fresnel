# Release operation and recovery

This document describes the supported manual release path and the recovery
procedure for partial publication. Preparation and publication are deliberately
serialized and use expected-SHA fast-forward gates, but GitHub Releases, Git
tags, GHCR and `main` are separate systems. A failure can therefore leave staged
objects that must be inspected before retrying.

## One-time repository configuration

Create a GitHub Actions environment named **`release`** in repository settings
and configure it with:

- at least one required reviewer who is not the workflow initiator;
- prevention of self-review, where available;
- selected deployment refs containing only the branch pattern
  `release/candidate-*` and tag pattern `[0-9]*.[0-9]*.[0-9]*`;
  workflow input validation additionally enforces exact semantic versions;
- no long-lived publication credentials—the workflow uses the scoped
  `GITHUB_TOKEN` and short-lived OIDC identity.

The **Release** orchestrator may run only from `main`. It creates a version-only
candidate branch and dispatches **Publish Release** at that exact branch. The
publication workflow independently proves that the candidate is a direct child
of the unchanged `main`, contains only Maven and citation version changes, and
matches `release.properties`.

After the release is published, **Release Packages** runs from the immutable
release tag. Its build and installer jobs remain read-only; a single
`publish-packages` job uses the same protected environment before ZIP, tar.gz,
MSI and Debian files can be attached or replaced.

Environment protection rules are repository settings and cannot be expressed in
workflow YAML. A live release is not approved for use until the required-reviewer
rule and both candidate-branch and release-tag restrictions are enabled.

Keep the ordinary `main` branch ruleset enabled with the required CI, Tests,
Coverage, E2E, Maven Site and packaging checks.

## Normal release

1. Merge the next-development version PR so Maven and `CITATION.cff` use an
   `X.Y.Z-SNAPSHOT` version and `release.properties` names `X.Y.Z`.
2. From `main`, run the **Release** workflow with `dry_run: true` and the desired
   `next_development_version` (or fallback increment). The release version is
   derived from Maven and `release.properties`; it is not entered manually.
   Review that rehearsal before starting the publishing run.
3. The orchestrator creates a commit changing only the four Maven POMs and
   `CITATION.cff`, runs the complete test build, pushes an ephemeral
   `release/candidate-*` branch and dispatches **Publish Release** at that exact
   commit.
4. Review the protected `release` environment deployment. Confirm the expected
   version, candidate branch and parent `main` SHA before approving it.
5. **Publish Release** checks out `github.sha`, repeats the full build, produces
   the JAR and multi-architecture image, publishes provenance attestations and an
   image SBOM, stages a draft GitHub Release, revalidates both refs and only then
   fast-forwards `main`.
6. After promotion it updates `latest`, publishes the GitHub Release, advances
   the maintenance branch, removes the candidate branch and opens the
   next-SNAPSHOT PR.
7. **Complete Release** matches the published tag to the exact publication
   workflow commit and dispatches **Release Packages** at that tag. After the
   read-only ZIP, tar.gz, MSI and Debian builds pass, review the protected
   `publish-packages` deployment so the verified files can be attached.

A dry run executes version validation and the complete candidate test build but
must not create a branch, tag, release, attestation, image or package.

## Verify published provenance

The attested workflow SHA must equal the peeled release tag commit. In a
preserved-draft recovery, that original artifact commit must be an ancestor of
reviewed `main`, with only the five explicitly allowed recovery files changed
after it. The recovery control SHA is never the attested artifact SHA.

After downloading the release JAR:

```bash
gh attestation verify backend-X.Y.Z.jar --repo carstenartur/Fresnel
```

After authenticating to GHCR:

```bash
gh attestation verify \
  oci://ghcr.io/carstenartur/fresnel:X.Y.Z \
  --repo carstenartur/Fresnel
```

Also verify the published checksum before running the JAR:

```bash
sha256sum --check backend-X.Y.Z.jar.sha256
```

## Determine the publication state

Set the intended version once:

```bash
VERSION=X.Y.Z
REPO=carstenartur/Fresnel
```

Inspect each independent object:

```bash
git ls-remote --heads --tags "https://github.com/${REPO}.git" \
  "refs/heads/main" "refs/heads/release/candidate-${VERSION}-*" \
  "refs/tags/${VERSION}"

gh release view "$VERSION" --repo "$REPO" \
  --json isDraft,isPrerelease,publishedAt,targetCommitish,url 2>/dev/null || true

gh api "/users/carstenartur/packages/container/fresnel/versions" \
  --paginate --jq '.[] | {id, tags: .metadata.container.tags, updated_at}' || true
```

Compare the candidate, tag, release commit and current `main` before deleting or
promoting anything. Never force-update `main` or an existing release tag during
recovery.

## Recovery by failure point

### No candidate branch exists

No repository publication side effect occurred. Fix the failing build or
configuration and rerun the workflow after `main` is green.

### Candidate branch exists, but no tag or release exists

The candidate commit is retained for diagnosis. Check the associated **Publish
Release** run. If it never started, no release artifacts were published. If it
failed after the container build, a versioned GHCR image and attestations may
already exist even though no tag exists.

After confirming that `main` was not advanced, delete the candidate branch before
retrying:

```bash
gh api "repos/${REPO}/git/refs/heads/release/candidate-..." \
  --method DELETE
```

A successful rerun of the same version replaces the versioned image tag with the
newly verified digest; compare digests and attestation workflow SHAs before
accepting it.

### Tag or draft release exists, but `main` was not advanced

Do not rerun the fresh release path while the tag exists. Inspect the draft,
candidate commit and attestations. For the preserved 0.1.8 incident, use the
numeric resume procedure below. When explicitly abandoning another attempt,
delete the draft first, then the tag and candidate branch:

```bash
gh release delete "$VERSION" --repo "$REPO" --yes

gh api "repos/${REPO}/git/refs/tags/${VERSION}" --method DELETE

gh api "repos/${REPO}/git/refs/heads/release/candidate-..." \
  --method DELETE
```

Retain logs and attestation links with the incident record. Delete or overwrite
the versioned GHCR tag only after confirming that it belongs to the abandoned
candidate commit.

### `main` contains the release commit, but the release is still draft

The version commit must not be reverted merely to rerun automation. For the
preserved 0.1.8 incident, use numeric resume below: it accepts a normal reviewed
merge that retains the exact original candidate, verifies original provenance,
promotes `latest` from the immutable digest and publishes the numeric draft ID.
Do not replace this with a promotion from a mutable version tag or a tag-based
draft lookup. For any other incident, stop and prepare a separately reviewed
recovery with its own recorded identities and cryptographic evidence.

### Release is published, but platform packages are missing

Do not recreate the release. For the preserved 0.1.8 incident, follow the numeric
resume procedure below; it resolves and reuses the matching package run at the
original tag. Inspect any completed unsuccessful run at its reported URL and
diagnose the failure before a separately authorized retry or admission through
the normal GitHub Actions path. The helper never retries or admits these runs.
The build and installer jobs are read-only and run from the existing release tag.
Approve the single protected `publish-packages` job only after all package
verification jobs have passed.

### Next-development PR is missing

Create a branch from the current reviewed `main` (after verifying it contains
the original artifact commit), bump all Maven modules and
`CITATION.cff` to the next patch `-SNAPSHOT`, update `release.properties`, and
open the standard **Prepare for next development iteration** PR. This housekeeping
failure does not invalidate an otherwise verified release.

## Preserved Fresnel 0.1.8 numeric resume

This is a deliberately incident-bound recovery in the existing `workflow_dispatch`
chain. It does not repair the fresh publisher's direct `main` PATCH for future
releases. Fresh publication still needs separate work to comply with the current
PR-required default-branch rules. Keep the existing Maven verification, security,
quality and coverage gates unchanged.

The original publisher created the annotated tag and uploaded both original
assets to a draft, then failed because `releases/tags/0.1.8` returned 404. The
release collection and numeric `releases/430398234` endpoint exposed the draft.
Fresh creation now resolves exactly one authenticated non-prerelease draft by
exact tag from the paginated release collection, never by newest release.

| Recorded identity | Exact value |
| --- | --- |
| Repository | `carstenartur/Fresnel` |
| Baseline main | `c3bca3ac5aa550e5af32cbc3c79aa54a567d1fab` |
| Original artifact candidate | `08c9a93a4a7bb3437377019711af1b1f9cc70773` |
| Original candidate branch | `release/candidate-0.1.8-41279140538-1` |
| Original annotated tag | `0.1.8` |
| Annotated tag object | `40f03ac0cc0b9cfe0bf6bd8cdbf9fe2daf9af94f` |
| Preserved numeric release ID | `430398234` |
| Original publication run | `41279261127`, attempt `1` |
| Original JAR asset ID | `697503326` |
| Original checksum asset ID | `697503328` |
| JAR SHA256 | `f9be8ad9c146f36c668f0cb229766512aa07b962d852178827196f99ffc8e2a7` |
| Versioned image digest | `sha256:f46673dddb83d0008d6f28c3aba899222016ba04bbede5b481def5005186e792` |

**Current configuration blocker (observed 2026-11-01):** the public environment
API returned `protection_rules: []`, `deployment_branch_policy: null`, and
`can_admins_bypass: true` for `release`. Required reviewers and selected deployment
refs are absent; this is known, not an unknown approval state. Numeric resume
fails closed before any control-ref creation, dispatch or publication write when
these documented protections are absent or unreadable. A dry run can report the
exact policy blocker while making zero remote writes. The helper neither changes
settings nor accepts approvals arrays or a successful old run as policy evidence.
An administrator must establish the documented reviewer/ref configuration through
the normal settings process before publishing can proceed. No alternative event,
identity, token, environment or browser login is part of recovery.

The policy check reads the actual environment and all deployment policies. It
requires a reviewer other than the workflow initiator, `prevent_self_review: true`,
selected deployment policies, and exactly the two branch/tag patterns listed
above. A team reviewer is accepted; GitHub enforces membership and self-review
protection. Other policy forms fail closed for manual inspection. Repository
settings and reviewer approval remain separate from artifact verification.

### Order and remaining manual entry point

1. Prepare one normal repair PR rooted in the exact preserved candidate. Its
   changes after that candidate are confined to `deploy-release.yml`,
   `publish-release.yml`, the publication-state helper and test, and this document.
   Satisfy the existing full Maven/security/coverage/quality gates and merge with
   a **merge commit**, preserving candidate ancestry. Squash/rebase recovery is
   rejected when it removes the original candidate from main history.
2. Inspect current `main`. Numeric resume verifies the original baseline parent,
   five-file metadata-only candidate (including the original release date),
   unchanged candidate branch, annotated tag object and peel, exact numeric
   release/run/repository/workflow binding, and recovery-only diff from candidate
   to reviewed main. Runtime, POM or other source drift after the candidate aborts.
3. From the GitHub Actions **Release** workflow, select current `main`, enter the
   following exact incident inputs, and first run with `dry_run: true`:

   ```text
   resume_release_id: 430398234
   resume_publication_run_id: 41279261127
   expected_candidate_sha: 08c9a93a4a7bb3437377019711af1b1f9cc70773
   next_development_version: 0.1.9-SNAPSHOT
   next_version_increment: patch
   dry_run: true
   ```

   The same canonical dispatch can be invoked with an authorized GitHub CLI:

   ```bash
   gh workflow run deploy-release.yml --repo carstenartur/Fresnel --ref main \
     -f resume_release_id=430398234 \
     -f resume_publication_run_id=41279261127 \
     -f expected_candidate_sha=08c9a93a4a7bb3437377019711af1b1f9cc70773 \
     -f next_development_version=0.1.9-SNAPSHOT \
     -f next_version_increment=patch -f dry_run=true
   ```

   Dispatch is the remaining manual entry point when the available connector
   cannot run `workflow_dispatch`. Do not create another trigger to get around it.
4. The rehearsal downloads the two original assets by numeric asset ID, hashes
   the actual bytes, checks the exact checksum contents and metadata digests,
   resolves the versioned image digest and cryptographically verifies **both**
   original attestations with `gh attestation verify`. Certificate policies pin
   repository, signer workflow, original candidate source and signer digests, and
   original candidate source ref. The verified SLSA receipt must identify the
   original publication run and subject hashes. The artifact candidate is always
   the recorded SHA, even though recovery executes different reviewed code.
5. Resolve the environment blocker and review the dry-run result. Run the same
   inputs with `dry_run: false` only when the normal protected path is ready. The
   orchestrator revalidates the complete state, creates a fresh
   `release/candidate-recovery-0.1.8-<run-id>-<attempt>` control branch **exactly at
   reviewed current main**, and dispatches the existing **Publish Release** there.
   This matches the existing candidate deployment pattern. Review its normal
   `release` environment deployment; no approval is inferred from creation of the
   control ref.
6. The resume publisher uses the existing publication concurrency domain and
   environment. Before writes it repeats all identity, downloaded-byte,
   cryptographic attestation, image and main/control-ref checks. It repeats the
   observation immediately before the first mutation and after image promotion.
   Comparisons retain stable release/asset identity, content and publication state;
   download counters and incidental API timestamps do not cause false drift.
   It promotes `latest` from `ghcr.io/carstenartur/fresnel@sha256:...`, publishes
   only numeric release `430398234`, and verifies the result. It performs no
   rebuild, re-attestation, tag creation, main update or preserved-ref deletion.
7. After core publication, resume explicitly dispatches and waits for the existing
   `release-package.yml` at unchanged tag `0.1.8`, with deterministic request ID
   `recovery-0.1.8-41279261127`. The unchanged **Complete Release** matcher compares
   its upstream control SHA with the peeled artifact tag SHA, finds no match, and
   skips automatic package dispatch for this recovery. Existing successful or
   running package requests are reused. Completed unsuccessful requests report
   their run URL, status and conclusion without retrying or admitting them.
   Diagnose them separately and obtain explicit authorization for any normal
   GitHub retry/admission; a subsequent numeric resume discovers and watches the
   same run after that external action. Ambiguous or wrong-head requests abort.
   Package attachment still uses its existing `release` environment job.
8. Housekeeping fast-forwards the maintenance branch to reviewed main only under
   the existing no-force ancestry rules. It prepares next-development metadata
   from reviewed main and opens the ordinary next-SNAPSHOT PR, retaining all
   recovery changes. A matching existing next-development branch/PR is validated
   and reused. No existing branch is overwritten; the original candidate stays.

A retry rechecks the complete evidence. If only `latest` promotion completed, it
publishes the preserved draft without rebuilding. If the exact numeric release
is already published with the correct digest, core publication is a no-op and
post-publication completion can continue. A published release with an unexpected
`latest` digest fails for inspection. Core success and package/housekeeping
failures are reported separately. If `main` moves or source changes beyond the
repair allowlist, stop and review the new state instead of weakening the lease.

The regression suites run from the existing canonical preflight. They qualify
only the release logic; local success does not qualify the full Maven gate,
Java/frontend/native matrix, live cryptographic verification, live policy or
protected approvals. The hosted required gates and release jobs remain necessary.

### Primary interfaces

The helper uses the documented
[GitHub CLI attestation verifier](https://cli.github.com/manual/gh_attestation_verify),
including `--source-digest`, `--signer-digest`, `--source-ref`, `--signer-workflow`,
`--cert-identity` and its `verificationResult.statement` JSON output. The policy
check uses [environment](https://docs.github.com/en/rest/deployments/environments)
and [deployment branch policy](https://docs.github.com/en/rest/deployments/branch-policies)
REST responses, rather than an invented approval receipt. Image resolution uses
[Buildx manifest JSON](https://docs.docker.com/reference/cli/docker/buildx/imagetools/inspect/).

## Incident record

For every partial release, record:

- orchestration and publication workflow URLs and initiating actor;
- expected starting `main` SHA and immutable candidate SHA;
- protected-environment reviewer and approval time for core and package writes;
- tag, draft/published release and GHCR image digests observed;
- attestations and checksums verified;
- cleanup or completion commands executed.

