#!/usr/bin/env python3
"""Exercise release metadata staging and diff guards in disposable Git repos."""

from __future__ import annotations

import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
POM = ET.parse(ROOT / "pom.xml").getroot()
MODULES = [node.text for node in POM.findall("m:modules/m:module", NS)]
POMS = ["pom.xml", *(f"{module}/pom.xml" for module in MODULES)]
METADATA = [*POMS, "CITATION.cff"]
CURRENT = POM.find("m:version", NS).text
BASE_VERSION = "7.8.9-SNAPSHOT"


def workflow_step(filename: str, name: str) -> str:
    """Read the actual shell body; no copied staging or guard implementation."""
    text = (ROOT / ".github/workflows" / filename).read_text()
    marker = f"      - name: {name}\n"
    start = text.index(marker) + len(marker)
    end = text.find("\n      - name:", start)
    step = text[start:end if end >= 0 else len(text)]
    body = step.split("        run: |\n", 1)[1]
    return "\n".join(line[10:] if line.startswith("          ") else line
                     for line in body.splitlines()).rstrip() + "\n"


class ReleaseMetadataCommitsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        for filename in [*METADATA, "release.properties"]:
            target = self.repo / filename
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text((ROOT / filename).read_text().replace(CURRENT, BASE_VERSION))
        (self.repo / "release.properties").write_text("next.release.version=7.8.9\n")
        self.git("init", "-q")
        self.git("config", "user.name", "Release contract test")
        self.git("config", "user.email", "release-contract@example.invalid")
        self.git("add", ".")
        self.git("commit", "-qm", "Baseline")
        self.base = self.git("rev-parse", "HEAD").strip()

    def git(self, *args: str) -> str:
        return subprocess.check_output(
            ["git", *args], cwd=self.repo, text=True, stderr=subprocess.STDOUT
        )

    def shell(self, script: str, **env: str) -> subprocess.CompletedProcess[str]:
        clean_env = {**os.environ, **env}
        clean_env.pop("BASH_ENV", None)
        return subprocess.run(
            ["bash", "--noprofile", "--norc", "-euo", "pipefail", "-c", script],
            cwd=self.repo, text=True, capture_output=True,
            env=clean_env, timeout=20
        )

    def change_metadata(self, version: str) -> None:
        for filename in METADATA:
            path = self.repo / filename
            path.write_text(path.read_text().replace(BASE_VERSION, version))

    def assert_staged_commit_complete(self, workflow: str, step: str, version: str) -> None:
        self.change_metadata(version)
        if version.endswith("-SNAPSHOT"):
            (self.repo / "release.properties").write_text(
                f"next.release.version={version.removesuffix('-SNAPSHOT')}\n"
            )
        body = workflow_step(workflow, step)
        commands = re.findall(r"^git add [^\n]+$", body, re.MULTILINE)
        self.assertEqual(1, len(commands), "The selected step must stage one metadata commit")
        result = self.shell(commands[0])
        self.assertEqual(0, result.returncode, result.stderr)
        self.git("commit", "-qm", "Prepared version")
        for filename in POMS:
            committed = ET.fromstring(self.git("show", f"HEAD:{filename}"))
            declared = committed.find("m:version", NS)
            if declared is None:
                declared = committed.find("m:parent/m:version", NS)
            self.assertEqual(version, declared.text, f"{filename} was left out of the commit")
        self.assertEqual("", self.git("diff", "--name-only"),
                         "Version changes must not remain only in the runner's worktree")

    def test_release_commit_contains_every_reactor_pom(self) -> None:
        self.assert_staged_commit_complete(
            "deploy-release.yml", "Create release metadata commit", "7.8.9"
        )

    def test_next_snapshot_commit_contains_every_reactor_pom(self) -> None:
        self.assert_staged_commit_complete(
            "publish-release.yml", "Prepare next development iteration", "7.8.10-SNAPSHOT"
        )

    def validate_candidate(self, workflow: str, unexpected_file: bool) -> subprocess.CompletedProcess[str]:
        self.change_metadata("7.8.9")
        if unexpected_file:
            (self.repo / "runtime-change.txt").write_text("not version metadata\n")
        self.git("add", ".")
        self.git("commit", "-qm", "Release candidate")
        head = self.git("rev-parse", "HEAD").strip()
        if workflow == "deploy-release.yml":
            body = workflow_step(workflow, "Validate candidate diff")
        else:
            complete = workflow_step(workflow, "Validate candidate provenance and metadata")
            # Execute the production diff guard in isolation from remote refs,
            # Maven and publication; the fixture supplies its real Git history.
            body = complete[complete.index("ACTUAL_FILES="):complete.index("\nCURRENT=")]
        return self.shell(
            body, STARTING_SHA=self.base, EXPECTED_MAIN_SHA=self.base,
            EVENT_SHA=head, GITHUB_OUTPUT=str(self.repo / "github-output")
        )

    def test_orchestrator_accepts_all_reactor_version_changes(self) -> None:
        result = self.validate_candidate("deploy-release.yml", False)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_publisher_accepts_all_reactor_version_changes(self) -> None:
        result = self.validate_candidate("publish-release.yml", False)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_orchestrator_still_rejects_non_metadata_changes(self) -> None:
        result = self.validate_candidate("deploy-release.yml", True)
        self.assertNotEqual(0, result.returncode, "Unexpected runtime changes were accepted")

    def test_publisher_still_rejects_non_metadata_changes(self) -> None:
        result = self.validate_candidate("publish-release.yml", True)
        self.assertNotEqual(0, result.returncode, "Unexpected runtime changes were accepted")


if __name__ == "__main__":
    unittest.main()
