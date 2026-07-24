# Secure release process

Fresnel releases use two explicit manual workflows. Neither workflow changes the
`main` branch directly.

1. **Prepare Release PR** validates a reviewed `main` SHA, runs the complete test
   suite with the proposed version, and opens a metadata-only pull request.
2. **Publish Release** runs only after that pull request has been reviewed and
   merged. It verifies the exact merge SHA, rebuilds and tests the release,
   creates or resumes a draft release, publishes checksummed and attested
   artifacts, publishes an SBOM/provenance-enabled container image, opens the
   next snapshot pull request, and only then publishes the draft.

## One-time repository configuration

Create a GitHub environment named **`production-release`** and configure:

- at least one required reviewer who is not the workflow initiator;
- deployment branches/tags restricted to the repository default branch;
- no unreviewed environment secrets;
- optional wait timer according to the project's change policy.

The workflows reference this environment, but environment protection rules are
repository settings and must be enabled by an administrator. Do not treat the
release path as production-approved until required reviewers are active.

## Step 1: select and review a main commit

Record the complete 40-character SHA at the tip of `main`. Review the required
checks, dependency changes, release notes, QA findings and the diff since the
previous release.

Do not reuse a SHA after `main` moves. Both workflows compare the supplied SHA
with the live remote `main` ref and abort before writing when they differ.

## Step 2: prepare the release pull request

Run **Prepare Release PR** with:

- `release_version`: semantic version without a prefix, such as `0.1.8`;
- `expected_main_sha`: the reviewed full SHA;
- `dry_run`: `true` for the first pass.

The validation job:

- requires a SNAPSHOT project version;
- verifies `CITATION.cff` matches the project version;
- rejects an existing tag or release;
- changes only the root/module POMs and `CITATION.cff`;
- runs `mvn -B -ntp verify` with no test-bypass input.

After the dry run succeeds, rerun with `dry_run=false`. The protected write job
opens `release/<version>` and a pull request. Review and merge this pull request
through the normal protected-branch process.

## Step 3: publish from the exact release merge

Record the full merge SHA of the release pull request. Run **Publish Release**
with that SHA and version.

The read-only build job:

- verifies remote `main` still equals the supplied SHA;
- requires project and citation versions to equal the release version;
- runs the complete Maven verification suite;
- builds the JAR and portable archives;
- generates and verifies `SHA256SUMS`;
- uploads one short-lived workflow artifact.

The `production-release` job then requires environment approval and receives the
minimum write capabilities needed for releases, packages, pull requests and
artifact attestations.

## Publication order and recovery

Publication is deliberately recoverable:

1. Create or reuse a **draft** release that targets the exact reviewed SHA.
2. Upload checksummed binary assets.
3. Generate GitHub artifact attestations for the JAR and archives.
4. Build and push version/SHA container tags with BuildKit SBOM and provenance.
5. Generate and push a registry attestation for the container digest.
6. Move the `latest` image tag to the attested digest.
7. Open a metadata-only next-SNAPSHOT pull request.
8. Publish the GitHub release as the final operation.

If a step before publication fails, the release stays draft. Rerun **Publish
Release** with the same version and SHA. The workflow accepts only a draft that
targets that exact SHA and uploads assets with replacement semantics.

A published release is never silently overwritten. A tag without the matching
recoverable draft is rejected and requires administrator investigation.

## Native installers

Publishing the release triggers **Release Packages**. Build and installer jobs
are read-only and run their tests and smoke checks first. A separate
`production-release` job downloads those exact workflow artifacts and attaches
them to the published release.

## Verification by consumers

Download release files and verify checksums:

```bash
sha256sum -c SHA256SUMS
```

Verify a GitHub artifact attestation:

```bash
gh attestation verify backend-<version>.jar \
  --repo carstenartur/Fresnel
```

Verify the container by digest rather than by a mutable tag:

```bash
docker pull ghcr.io/carstenartur/fresnel@sha256:<digest>
gh attestation verify \
  oci://ghcr.io/carstenartur/fresnel@sha256:<digest> \
  --repo carstenartur/Fresnel
```

The release workflow records the container digest in its job summary.

## Dependency update policy

All external GitHub Actions use complete reviewed commit SHAs, with the human
version retained in a comment. Docker build/runtime bases use explicit tags plus
multi-platform SHA-256 digests. Dependabot opens reviewed updates for:

- GitHub Actions;
- Docker base images;
- Maven dependencies/plugins;
- frontend npm dependencies.

`packaging/verify-release-security.py` runs in CI and rejects mutable action
references, unpinned Docker bases, direct writes to `main`, release test bypasses,
missing attestations, broad workflow-level write permissions and missing
Dependabot ecosystems.

## Emergency handling

Do not delete, retarget or republish a release automatically.

- **Failure while draft:** inspect logs, correct the source workflow through a
  pull request, then rerun with the same exact SHA if still valid.
- **`main` moved:** stop and review the new SHA; never force the stale release.
- **Version/tag collision:** investigate manually; the workflow intentionally
  refuses to guess.
- **Published artifact defect:** publish a new patch version. Do not replace a
  published version in place.
- **Compromised workflow dependency:** revoke affected packages/releases,
  rotate credentials, update the pinned SHA/digest through review and rebuild a
  new version with fresh attestations.
