#!/usr/bin/env python3
"""Update CITATION.cff version metadata during the release workflow."""

from __future__ import annotations

import argparse
import re
from datetime import date
from pathlib import Path


def upsert_line(text: str, key: str, line: str) -> str:
    pattern = rf"^{re.escape(key)}:.*$"
    if re.search(pattern, text, flags=re.MULTILINE):
        return re.sub(pattern, line, text, flags=re.MULTILINE)
    return text.rstrip() + "\n" + line + "\n"


def remove_line(text: str, key: str) -> str:
    pattern = rf"^{re.escape(key)}:.*\n?"
    return re.sub(pattern, "", text, flags=re.MULTILINE)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--file", default="CITATION.cff")
    parser.add_argument("--version", required=True)
    parser.add_argument(
        "--snapshot",
        action="store_true",
        help="Remove date-released metadata for the next development iteration.",
    )
    args = parser.parse_args()

    path = Path(args.file)
    text = path.read_text(encoding="utf-8")
    text = upsert_line(text, "version", f'version: "{args.version}"')

    if args.snapshot:
        text = remove_line(text, "date-released")
    else:
        text = upsert_line(text, "date-released", f"date-released: {date.today().isoformat()}")

    path.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
