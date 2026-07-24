#!/usr/bin/env python3
"""Fail CI when workflow actions or Docker base images use mutable references."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"

USES_PATTERN = re.compile(r"^\s*(?:-\s*)?uses:\s*([^\s#]+)")
ACTION_SHA_PATTERN = re.compile(r"^[^@\s]+@[0-9a-f]{40}$")
DOCKER_ACTION_DIGEST_PATTERN = re.compile(r"^docker://[^@\s]+@sha256:[0-9a-f]{64}$")
FROM_PATTERN = re.compile(r"^\s*FROM\s+([^\s]+)", re.IGNORECASE)
IMAGE_DIGEST_PATTERN = re.compile(r"^[^@\s]+@sha256:[0-9a-f]{64}$")


def check_workflow_actions(errors: list[str]) -> None:
    workflow_paths = sorted((*WORKFLOWS.glob("*.yml"), *WORKFLOWS.glob("*.yaml")))
    if not workflow_paths:
        errors.append("No GitHub Actions workflows found")
        return

    for workflow in workflow_paths:
        for line_number, line in enumerate(
            workflow.read_text(encoding="utf-8").splitlines(), start=1
        ):
            match = USES_PATTERN.match(line)
            if not match:
                continue
            reference = match.group(1).strip("'\"")
            if reference.startswith("./"):
                continue
            if reference.startswith("docker://"):
                if not DOCKER_ACTION_DIGEST_PATTERN.fullmatch(reference):
                    errors.append(
                        f"{workflow.relative_to(ROOT)}:{line_number}: "
                        f"Docker action is not pinned by sha256 digest: {reference}"
                    )
                continue
            if not ACTION_SHA_PATTERN.fullmatch(reference):
                errors.append(
                    f"{workflow.relative_to(ROOT)}:{line_number}: "
                    f"action is not pinned to a full commit SHA: {reference}"
                )


def check_dockerfile_bases(errors: list[str]) -> None:
    dockerfile = ROOT / "Dockerfile"
    if not dockerfile.is_file():
        errors.append("Dockerfile is missing")
        return

    for line_number, line in enumerate(
        dockerfile.read_text(encoding="utf-8").splitlines(), start=1
    ):
        match = FROM_PATTERN.match(line)
        if not match:
            continue
        image = match.group(1)
        if image.lower() == "scratch":
            continue
        if not IMAGE_DIGEST_PATTERN.fullmatch(image):
            errors.append(
                f"Dockerfile:{line_number}: base image is not pinned by sha256 digest: "
                f"{image}"
            )


def require_tokens(
    workflow: Path, required: dict[str, str], errors: list[str]
) -> str | None:
    if not workflow.is_file():
        errors.append(f"Required workflow is missing: {workflow.relative_to(ROOT)}")
        return None

    content = workflow.read_text(encoding="utf-8")
    for token, purpose in required.items():
        if token not in content:
            errors.append(
                f"{workflow.name} is missing {token!r} required for {purpose}"
            )
    return content


def reject_test_skips(workflow: Path, content: str | None, errors: list[str]) -> None:
    if content is None:
        return
    forbidden = {
        "skip_tests": "production releases must never offer a test-skip path",
        "-DskipTests": "the release build must execute tests",
    }
    for token, reason in forbidden.items():
        if token in content:
            errors.append(f"{workflow.name} contains {token!r}: {reason}")


def check_release_invariants(errors: list[str]) -> None:
    orchestrator = WORKFLOWS / "deploy-release.yml"
    orchestrator_content = require_tokens(
        orchestrator,
        {
            "group: fresnel-release-orchestration": "serialized release preparation",
            "refs/heads/main": "main-only manual dispatch",
            "publish-release.yml": "separate immutable-candidate publication",
            "Build and verify candidate with tests": "candidate test gate",
            "gh run watch": "publication result propagation",
            "release/candidate-": "isolated candidate branch",
        },
        errors,
    )
    reject_test_skips(orchestrator, orchestrator_content, errors)

    publisher = WORKFLOWS / "publish-release.yml"
    publisher_content = require_tokens(
        publisher,
        {
            "environment: release": "protected publication approval",
            "group: fresnel-release-publication": "serialized publication",
            "ref: ${{ github.sha }}": "exact candidate checkout",
            "EXPECTED_MAIN_SHA": "stale-main protection",
            "attest-build-provenance": "artifact provenance",
            "provenance: mode=max": "container provenance",
            "sbom: true": "container SBOM",
            "ACTUAL_FILES": "version-only candidate validation",
            "Fast-forward main to candidate": "non-force main promotion",
        },
        errors,
    )
    reject_test_skips(publisher, publisher_content, errors)

    packager = WORKFLOWS / "release-package.yml"
    packager_content = require_tokens(
        packager,
        {
            "Test and build jar + ZIP/tar.gz": "tested release package production",
            "publish-packages": "single package publication job",
            "environment: release": "protected package attachment approval",
            "refs/tags/${TAG}": "immutable-tag package execution",
            "Attach files to GitHub Release": "explicit release attachment step",
        },
        errors,
    )
    reject_test_skips(packager, packager_content, errors)

    completer = WORKFLOWS / "complete-release.yml"
    require_tokens(
        completer,
        {
            "workflows: [Publish Release]": "completion tied to publication workflow",
            "UPSTREAM_HEAD_SHA": "exact release-commit correlation",
            "release-package.yml": "platform package completion",
            '--ref "$RELEASE_TAG"': "package dispatch from the immutable release tag",
        },
        errors,
    )


def main() -> int:
    errors: list[str] = []
    check_workflow_actions(errors)
    check_dockerfile_bases(errors)
    check_release_invariants(errors)

    if errors:
        print("Supply-chain pin validation failed:")
        for error in errors:
            print(f"- {error}")
        return 1

    print("All workflow actions and Docker base images use immutable references.")
    print("Two-stage release provenance and protected package invariants are present.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
