# Release operation and recovery

This document describes the supported manual release path and the recovery
procedure for partial publication. The workflow is deliberately serialized and
uses an expected-SHA fast-forward gate, but GitHub Releases, Git tags, GHCR and
`main` are separate systems. A failure can therefore leave staged objects that
must be inspected before retrying.

## One-time repository configuration

Create a GitHub Actions environment named **`release`** in repository settings
and configure it with:

- at least one required reviewer who is not the workflow initiator;
- prevention of self-review, where available;
- deployment branches restricted to `main`;
- no long-lived publication credentials—the workflow uses the scoped
  `GITHUB_TOKEN` and short-lived OIDC identity.

The workflow references this environment, but environment protection rules are
repository settings and cannot be expressed in workflow YAML. A live release is
not approved for use until the required-reviewer rule is enabled.

Keep the ordinary `main` branch ruleset enabled with the required CI, Tests,
Coverage, E2E, Maven Site and packaging checks.

## Normal release

1. Merge the next-development version PR so Maven and `CITATION.cff` use an
   `X.Y.Z-SNAPSHOT` version and `release.properties` names `X.Y.Z`.
2. Run the **Release** workflow with exactly that `X.Y.Z` value.
3. Review the protected `release` environment deployment.
4. The workflow executes all tests, creates an isolated candidate commit, builds
   the JAR and multi-architecture image, publishes provenance attestations,
   stages a draft GitHub Release, verifies that `main` has not moved, and only
   then fast-forwards `main`.
5. After promotion it updates `latest`, publishes the GitHub Release, advances
   the maintenance branch and opens the next-SNAPSHOT PR.
6. **Complete Release** dispatches **Release Packages** and waits for the ZIP,
   tar.gz, MSI and Debian package jobs to attach their verified outputs.

A dry run executes version validation, tests and local artifact creation but
must not create refs, releases, attestations or packages.

## Verify published provenance

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

Also verify the checked-in checksum before running the JAR:

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

gh api "/orgs/carstenartur/packages/container/fresnel/versions" \
  --paginate --jq '.[] | {id, tags: .metadata.container.tags, updated_at}' || true
```

Compare the tag, release commit and current `main` before deleting or promoting
anything. Never force-update `main` or an existing release tag during recovery.

## Recovery by failure point

### No candidate branch exists

No repository publication side effect occurred. Fix the failing build or
configuration and rerun the workflow after `main` is green.

### Candidate branch exists, but no tag or release exists

The candidate commit is retained for diagnosis. After confirming that `main`
was not advanced, delete the branch before retrying:

```bash
gh api "repos/${REPO}/git/refs/heads/release/candidate-..." \
  --method DELETE
```

A versioned container image may already exist. A successful rerun of the same
version replaces that tag with the newly verified digest; compare digests before
accepting it.

### Tag or draft release exists, but `main` was not advanced

Do not rerun while the tag exists. Inspect the draft and candidate commit. When
abandoning the attempt, delete the draft first, then the tag and candidate
branch:

```bash
gh release delete "$VERSION" --repo "$REPO" --yes

gh api "repos/${REPO}/git/refs/tags/${VERSION}" --method DELETE

gh api "repos/${REPO}/git/refs/heads/release/candidate-..." \
  --method DELETE
```

Retain logs and attestation links with the incident record. Delete or overwrite
the versioned GHCR tag only after confirming that it belongs to the abandoned
attempt.

### `main` contains the release commit, but the release is still draft

The version commit must not be reverted merely to rerun automation. Confirm that
the tag points to the current `main`, verify the JAR and image attestations, then
complete the missing publication steps:

```bash
docker buildx imagetools create \
  --tag ghcr.io/carstenartur/fresnel:latest \
  "ghcr.io/carstenartur/fresnel:${VERSION}"

gh release edit "$VERSION" --repo "$REPO" --draft=false
```

Then dispatch the package completion manually:

```bash
gh workflow run release-package.yml --repo "$REPO" --ref main \
  -f release_tag="$VERSION" \
  -f request_id="manual-recovery-${VERSION}"
```

### Release is published, but platform packages are missing

Do not recreate the release. Dispatch `release-package.yml` with the existing
release tag as shown above. Its build jobs are read-only; separate attachment
jobs receive write permission only after the tag and project version match.

### Next-development PR is missing

Create a branch from the release commit, bump all Maven modules and
`CITATION.cff` to the next patch `-SNAPSHOT`, update `release.properties`, and
open the standard **Prepare for next development iteration** PR. This housekeeping
failure does not invalidate an otherwise verified release.

## Incident record

For every partial release, record:

- workflow run URL and initiating actor;
- expected starting `main` SHA and release commit SHA;
- tag, draft/published release and GHCR image digests observed;
- attestations and checksums verified;
- cleanup or completion commands executed;
- reviewer who approved the recovery decision.
