#!/usr/bin/env python3
"""Update citation metadata during the release workflow."""

from __future__ import annotations

import argparse
import re
from datetime import date
from pathlib import Path

ORCID_ID = "0009-0005-1047-6381"
ORCID_URL = "https://orcid.org/" + ORCID_ID


def upsert_line(text: str, key: str, line: str) -> str:
    pattern = rf"^{re.escape(key)}:.*$"
    if re.search(pattern, text, flags=re.MULTILINE):
        return re.sub(pattern, line, text, flags=re.MULTILINE)
    return text.rstrip() + "\n" + line + "\n"


def remove_line(text: str, key: str) -> str:
    pattern = rf"^{re.escape(key)}:.*\n?"
    return re.sub(pattern, "", text, flags=re.MULTILINE)


def update_citation_cff(path: Path, version: str, snapshot: bool) -> None:
    text = path.read_text(encoding="utf-8")
    text = upsert_line(text, "version", f'version: "{version}"')

    if snapshot:
        text = remove_line(text, "date-released")
    else:
        text = upsert_line(text, "date-released", f"date-released: {date.today().isoformat()}")

    path.write_text(text, encoding="utf-8")


def update_citation_md(path: Path, version: str, snapshot: bool) -> None:
    if not path.exists():
        return
    text = path.read_text(encoding="utf-8")
    text = re.sub(
        r"(Carsten Hammer\. \*\*Fresnel\*\*\. Version )[0-9A-Za-z.-]+(\. 2026\.)",
        rf"\g<1>{version}\2",
        text,
    )
    text = re.sub(r"(  version\s+= \{)[^}]+(\},)", rf"\g<1>{version}\2", text)

    if snapshot:
        text = re.sub(r"^  date\s+= \{[^}]+\},\n", "", text, flags=re.MULTILINE)
    elif re.search(r"^  date\s+= \{[^}]+\},$", text, flags=re.MULTILINE):
        text = re.sub(
            r"^  date\s+= \{[^}]+\},$",
            f"  date         = {{{date.today().isoformat()}}},",
            text,
            flags=re.MULTILINE,
        )
    else:
        text = re.sub(
            r"^(  version\s+= \{[^}]+\},)$",
            rf"\1\n  date         = {{{date.today().isoformat()}}},",
            text,
            flags=re.MULTILINE,
        )

    if "ORCID" not in text:
        text = text.replace(
            "## What to cite\n",
            f"## Author identifier\n\nCarsten Hammer's ORCID iD is [{ORCID_URL}]({ORCID_URL}).\n\n## What to cite\n",
        )
    if "  orcid" not in text:
        text = re.sub(
            r"(  author\s+= \{Hammer, Carsten\},)",
            rf"\1\n  orcid        = {{{ORCID_URL}}},",
            text,
            flags=re.MULTILINE,
        )

    path.write_text(text, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--file", default="CITATION.cff")
    parser.add_argument("--version", required=True)
    parser.add_argument(
        "--snapshot",
        action="store_true",
        help="Remove release-date metadata for the next development iteration.",
    )
    args = parser.parse_args()

    update_citation_cff(Path(args.file), args.version, args.snapshot)
    update_citation_md(Path("CITATION.md"), args.version, args.snapshot)


if __name__ == "__main__":
    main()
