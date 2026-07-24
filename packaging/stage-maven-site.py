#!/usr/bin/env python3
"""Stage and validate the generated multi-module Maven site.

The script is intentionally independent of GitHub Actions so the exact same
report-staging path can be executed locally, in pull-request checks and before
publication from the default branch.
"""

from __future__ import annotations

import argparse
import csv
import html
import json
import shutil
import sys
from dataclasses import dataclass
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SITE = ROOT / "site"
MODULES = ("optics-core", "backend")


@dataclass(frozen=True)
class CoverageRow:
    module: str
    covered: int
    missed: int

    @property
    def total(self) -> int:
        return self.covered + self.missed

    @property
    def percent(self) -> float:
        if self.total == 0:
            raise ValueError(f"No coverage instructions were recorded for {self.module}")
        return self.covered / self.total * 100.0


@dataclass(frozen=True)
class TestSuiteRow:
    module: str
    name: str
    tests: int
    failures: int
    errors: int
    skipped: int

    @property
    def failed(self) -> int:
        return self.failures + self.errors

    @property
    def passed(self) -> int:
        return max(self.tests - self.failed - self.skipped, 0)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--minimum-coverage",
        type=float,
        default=0.0,
        help="Fail when aggregate JaCoCo instruction coverage is below this percentage.",
    )
    return parser.parse_args()


def require_directory(path: Path, description: str) -> Path:
    if not path.is_dir():
        raise RuntimeError(f"Missing {description}: {path.relative_to(ROOT)}")
    return path


def require_file(path: Path, description: str) -> Path:
    if not path.is_file():
        raise RuntimeError(f"Missing {description}: {path.relative_to(ROOT)}")
    return path


def write_json(path: Path, payload: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, separators=(",", ":")) + "\n", encoding="utf-8")


def esc(value: object) -> str:
    return html.escape(str(value), quote=True)


def stage_module_sites() -> None:
    if SITE.exists():
        shutil.rmtree(SITE)
    SITE.mkdir(parents=True)

    for module in MODULES:
        source = require_directory(
            ROOT / module / "target" / "site", f"generated Maven site for {module}"
        )
        require_file(source / "index.html", f"Maven site index for {module}")
        shutil.copytree(source, SITE / module)


def write_root_index() -> None:
    (SITE / "index.html").write_text(
        """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>Fresnel — Maven Site</title>
  <style>
    body { font-family: system-ui, sans-serif; max-width: 760px; margin: 2em auto; padding: 0 1em; }
    h1 { color: #0066cc; }
    li { margin: .5em 0; }
    a { color: #0066cc; }
  </style>
</head>
<body>
  <h1>Fresnel — Maven Site</h1>
  <p>Per-module Maven reports, generated from the same validated source revision.</p>
  <ul>
    <li><a href="optics-core/index.html">optics-core</a></li>
    <li><a href="backend/index.html">backend</a></li>
    <li><a href="coverage/index.html">Coverage summary</a></li>
    <li><a href="tests/index.html">Test summary</a></li>
  </ul>
</body>
</html>
""",
        encoding="utf-8",
    )


def read_coverage() -> list[CoverageRow]:
    rows: list[CoverageRow] = []
    for module in MODULES:
        csv_path = require_file(
            ROOT / module / "target" / "site" / "jacoco" / "jacoco.csv",
            f"JaCoCo CSV report for {module}",
        )
        covered = 0
        missed = 0
        with csv_path.open(newline="", encoding="utf-8") as handle:
            reader = csv.DictReader(handle)
            required_columns = {"INSTRUCTION_COVERED", "INSTRUCTION_MISSED"}
            if reader.fieldnames is None or not required_columns.issubset(reader.fieldnames):
                raise RuntimeError(f"Unexpected JaCoCo CSV columns in {csv_path.relative_to(ROOT)}")
            for row in reader:
                covered += int(row.get("INSTRUCTION_COVERED", 0) or 0)
                missed += int(row.get("INSTRUCTION_MISSED", 0) or 0)
        coverage = CoverageRow(module, covered, missed)
        _ = coverage.percent  # validates that data was produced
        rows.append(coverage)
    return rows


def coverage_color(percent: float) -> str:
    if percent >= 80.0:
        return "brightgreen"
    if percent >= 60.0:
        return "yellow"
    return "red"


def write_coverage_summary(rows: list[CoverageRow]) -> float:
    covered = sum(row.covered for row in rows)
    total = sum(row.total for row in rows)
    if total == 0:
        raise RuntimeError("No aggregate JaCoCo coverage data was generated")
    percent = covered / total * 100.0

    write_json(
        SITE / "badges" / "coverage.json",
        {
            "schemaVersion": 1,
            "label": "coverage",
            "message": f"{percent:.1f}%",
            "color": coverage_color(percent),
        },
    )

    body = "\n".join(
        "<tr>"
        f"<td><a href='../{esc(row.module)}/jacoco/index.html'>{esc(row.module)}</a></td>"
        f"<td>{row.percent:.1f}%</td>"
        f"<td>{row.covered}/{row.total}</td>"
        "</tr>"
        for row in rows
    )
    target = SITE / "coverage" / "index.html"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(
        f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>Fresnel Coverage</title>
<style>body{{font-family:system-ui,sans-serif;margin:2rem;max-width:960px}}table{{border-collapse:collapse;width:100%}}td,th{{border:1px solid #ddd;padding:.5rem;text-align:left}}th{{background:#f6f8fa}}</style>
</head><body><h1>Fresnel Coverage</h1>
<p>Total instruction coverage: <strong>{percent:.1f}%</strong></p>
<table><thead><tr><th>Module</th><th>Instruction coverage</th><th>Covered/total instructions</th></tr></thead><tbody>{body}</tbody></table>
</body></html>
""",
        encoding="utf-8",
    )
    return percent


def test_suite_nodes(root: ET.Element) -> list[ET.Element]:
    if root.tag.endswith("testsuite"):
        return [root]
    suites = [child for child in root if child.tag.endswith("testsuite")]
    if not suites:
        raise RuntimeError(f"Unsupported JUnit XML root element: {root.tag}")
    return suites


def read_tests() -> list[TestSuiteRow]:
    rows: list[TestSuiteRow] = []
    for module in MODULES:
        report_dir = require_directory(
            ROOT / module / "target" / "surefire-reports", f"Surefire report directory for {module}"
        )
        report_files = sorted(report_dir.glob("TEST-*.xml"))
        if not report_files:
            raise RuntimeError(f"No Surefire XML reports found for {module}")
        for path in report_files:
            try:
                root = ET.parse(path).getroot()
            except ET.ParseError as exc:
                raise RuntimeError(f"Malformed JUnit XML {path.relative_to(ROOT)}: {exc}") from exc
            for suite in test_suite_nodes(root):
                rows.append(
                    TestSuiteRow(
                        module=module,
                        name=suite.get("name", path.stem),
                        tests=int(suite.get("tests", 0)),
                        failures=int(suite.get("failures", 0)),
                        errors=int(suite.get("errors", 0)),
                        skipped=int(suite.get("skipped", 0)),
                    )
                )
    if not rows:
        raise RuntimeError("No JUnit test suites were parsed")
    return rows


def write_test_summary(rows: list[TestSuiteRow]) -> tuple[int, int, int, int]:
    tests = sum(row.tests for row in rows)
    failed = sum(row.failed for row in rows)
    skipped = sum(row.skipped for row in rows)
    passed = sum(row.passed for row in rows)
    if tests == 0:
        raise RuntimeError("JUnit reports contain zero tests")

    if failed:
        message = f"{failed}/{tests} failed"
        color = "red"
    elif skipped:
        message = f"{passed} passed, {skipped} skipped"
        color = "yellowgreen"
    else:
        message = f"{passed} passed"
        color = "brightgreen"

    write_json(
        SITE / "badges" / "tests.json",
        {"schemaVersion": 1, "label": "tests", "message": message, "color": color},
    )

    body = "\n".join(
        "<tr>"
        f"<td>{esc(row.module)}</td>"
        f"<td>{esc(row.name)}</td>"
        f"<td>{row.tests}</td>"
        f"<td>{row.failed}</td>"
        f"<td>{row.skipped}</td>"
        "</tr>"
        for row in rows
    )
    target = SITE / "tests" / "index.html"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(
        f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>Fresnel Tests</title>
<style>body{{font-family:system-ui,sans-serif;margin:2rem;max-width:1200px}}table{{border-collapse:collapse;width:100%}}td,th{{border:1px solid #ddd;padding:.5rem;text-align:left}}th{{background:#f6f8fa}}</style>
</head><body><h1>Fresnel Tests</h1>
<p><strong>{passed}</strong> passed, <strong>{failed}</strong> failed, <strong>{skipped}</strong> skipped, <strong>{tests}</strong> total.</p>
<table><thead><tr><th>Module</th><th>Suite</th><th>Tests</th><th>Failed</th><th>Skipped</th></tr></thead><tbody>{body}</tbody></table>
</body></html>
""",
        encoding="utf-8",
    )
    return tests, passed, failed, skipped


def main() -> int:
    args = parse_args()
    if not 0.0 <= args.minimum_coverage <= 100.0:
        raise RuntimeError("--minimum-coverage must be between 0 and 100")

    stage_module_sites()
    write_root_index()
    coverage_rows = read_coverage()
    coverage_percent = write_coverage_summary(coverage_rows)
    test_rows = read_tests()
    tests, passed, failed, skipped = write_test_summary(test_rows)

    write_json(
        SITE / "qa-summary.json",
        {
            "coveragePercent": round(coverage_percent, 4),
            "minimumCoveragePercent": args.minimum_coverage,
            "tests": tests,
            "passed": passed,
            "failed": failed,
            "skipped": skipped,
            "modules": list(MODULES),
        },
    )

    print(
        f"Staged Maven Site: coverage={coverage_percent:.2f}% "
        f"tests={tests} passed={passed} failed={failed} skipped={skipped}"
    )
    if failed:
        raise RuntimeError(f"JUnit reports contain {failed} failed/error tests")
    if coverage_percent < args.minimum_coverage:
        raise RuntimeError(
            f"Instruction coverage {coverage_percent:.2f}% is below the "
            f"required {args.minimum_coverage:.2f}%"
        )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
