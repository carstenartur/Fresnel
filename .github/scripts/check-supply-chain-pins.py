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


def check_release_invariants(errors: list[str]) -> None:
    release_workflow = WORKFLOWS / "deploy-release.yml"
    if not release_workflow.is_file():
        errors.append("Release workflow is missing")
        return

    content = release_workflow.read_text(encoding="utf-8")
    forbidden = {
        "skip_tests": "production releases must never offer a test-skip path",
        "-DskipTests": "the release build must execute tests",
    }
    for token, reason in forbidden.items():
        if token in content:
            errors.append(f"deploy-release.yml contains {token!r}: {reason}")

    required = {
        "environment: release": "protected release environment",
        "group: fresnel-release": "serialized releases",
        "refs/heads/main": "main-only manual dispatch",
        "attest-build-provenance": "artifact provenance",
        "provenance: mode=max": "container provenance",
        "sbom: true": "container SBOM",
        "EXPECTED_MAIN_SHA": "stale-main protection",
    }
    for token, purpose in required.items():
        if token not in content:
            errors.append(
                f"deploy-release.yml is missing {token!r} required for {purpose}"
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
    print("Release workflow safety invariants are present.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
