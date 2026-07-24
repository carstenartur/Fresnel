#!/usr/bin/env python3
"""Fail CI when release or supply-chain hardening invariants regress."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github" / "workflows"
FULL_SHA = re.compile(r"^[0-9a-f]{40}$")
FROM_WITH_DIGEST = re.compile(
    r"^FROM\s+\S+@sha256:[0-9a-f]{64}(?:\s+AS\s+\S+)?$", re.IGNORECASE
)
USES = re.compile(r"^\s*-?\s*uses:\s*([^\s#]+)", re.MULTILINE)
DIRECT_MAIN_WRITE = re.compile(
    r"(?:git/refs/heads/main|"
    r"git\s+push[^\n]*(?:refs/heads/)?main(?:\s|$)|"
    r"git\s+(?:checkout|switch)\s+main(?:\s|$)|"
    r"git\s+update-ref\s+refs/heads/main)",
    re.IGNORECASE,
)


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def read(path: Path) -> str:
    if not path.is_file():
        fail(f"required file is missing: {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


def verify_action_pins() -> None:
    violations: list[str] = []
    for path in sorted(WORKFLOWS.glob("*.y*ml")):
        text = read(path)
        if re.search(r"(?m)^permissions:\n  contents:\s+write\s*$", text):
            violations.append(
                f"{path.name}: workflow-level contents permission is writable"
            )
        for value in USES.findall(text):
            if value.startswith("./") or value.startswith("docker://"):
                continue
            if "@" not in value:
                violations.append(f"{path.name}: action has no ref: {value}")
                continue
            action, ref = value.rsplit("@", 1)
            if not FULL_SHA.fullmatch(ref):
                violations.append(f"{path.name}: {action} uses mutable ref {ref}")
    if violations:
        fail(
            "GitHub Actions policy violations:\n  " + "\n  ".join(violations)
        )


def verify_docker_pins() -> None:
    dockerfile = ROOT / "Dockerfile"
    lines = read(dockerfile).splitlines()
    from_lines = [
        line.strip() for line in lines if line.lstrip().upper().startswith("FROM ")
    ]
    if not from_lines:
        fail("Dockerfile contains no FROM instructions")
    bad = [line for line in from_lines if not FROM_WITH_DIGEST.fullmatch(line)]
    if bad:
        fail(
            "every Docker base image must use a reviewed sha256 digest:\n  "
            + "\n  ".join(bad)
        )


def reject_release_bypasses(path: Path, text: str) -> None:
    for token in ("skip_tests", "--skip-tests"):
        if token in text:
            fail(f"{path.name} contains forbidden release bypass: {token}")
    match = DIRECT_MAIN_WRITE.search(text)
    if match:
        fail(
            f"{path.name} directly mutates or checks out main: {match.group(0)!r}"
        )


def verify_prepare_workflow() -> None:
    path = WORKFLOWS / "prepare-release.yml"
    text = read(path)
    required = [
        "expected_main_sha",
        "mvn -B -ntp verify",
        "environment: production-release",
        "pull-requests: write",
        "contents: write",
        "release/",
    ]
    for token in required:
        if token not in text:
            fail(f"prepare-release.yml is missing required control: {token}")
    reject_release_bypasses(path, text)


def verify_publish_workflow() -> None:
    path = WORKFLOWS / "deploy-release.yml"
    text = read(path)
    required = [
        "expected_main_sha",
        "environment: production-release",
        "mvn -B -ntp verify",
        "attestations: write",
        "id-token: write",
        "subject-path:",
        "subject-digest:",
        "push-to-registry: true",
        "--draft",
        "--draft=false",
    ]
    for token in required:
        if token not in text:
            fail(f"deploy-release.yml is missing required control: {token}")
    reject_release_bypasses(path, text)
    if not re.search(r"(?m)^permissions:\n  contents:\s+read\s*$", text):
        fail("deploy-release.yml must default to read-only repository contents")


def verify_release_packages() -> None:
    path = WORKFLOWS / "release-package.yml"
    text = read(path)
    if "environment: production-release" not in text:
        fail("release-package.yml release writes are not environment-gated")
    if "-Pfrontend,release-package -pl backend -am verify" not in text:
        fail("release-package.yml does not run tests before packaging")


def verify_dependabot() -> None:
    text = read(ROOT / ".github" / "dependabot.yml")
    for ecosystem in ("github-actions", "docker", "maven", "npm"):
        if not re.search(
            rf"package-ecosystem:\s*[\"']?{re.escape(ecosystem)}[\"']?", text
        ):
            fail(f"Dependabot is not configured for {ecosystem}")


def main() -> None:
    verify_action_pins()
    verify_docker_pins()
    verify_prepare_workflow()
    verify_publish_workflow()
    verify_release_packages()
    verify_dependabot()
    print("Release and supply-chain policy checks passed")


if __name__ == "__main__":
    main()
