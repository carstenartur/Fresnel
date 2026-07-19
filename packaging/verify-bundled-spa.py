#!/usr/bin/env python3
"""Verify that a running packaged Fresnel application serves its bundled SPA correctly."""

from __future__ import annotations

import argparse
import base64
import re
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass


@dataclass(frozen=True)
class Response:
    content_type: str
    body: bytes


def basic_authorization(username: str, password: str) -> str:
    token = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
    return f"Basic {token}"


def fetch(base_url: str, path: str, authorization: str) -> Response:
    request = urllib.request.Request(
        base_url.rstrip("/") + path,
        headers={"Authorization": authorization},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return Response(response.headers.get_content_type(), response.read())


def verify(base_url: str, username: str, password: str) -> None:
    authorization = basic_authorization(username, password)
    index = fetch(base_url, "/", authorization)
    if index.content_type != "text/html":
        raise AssertionError(f"Expected text/html for /, got {index.content_type}")
    if b'id="root"' not in index.body and b"id='root'" not in index.body:
        raise AssertionError("Bundled index does not contain the React root element")

    route = fetch(base_url, "/plugins/zone-plate", authorization)
    if route.content_type != "text/html" or route.body != index.body:
        raise AssertionError("The stable plugin route does not resolve to the bundled SPA")

    asset_paths = sorted(set(
        match.decode("utf-8")
        for match in re.findall(rb"/assets/[^\"' ]+\.(?:js|css)", index.body)
    ))
    if not any(path.endswith(".js") for path in asset_paths):
        raise AssertionError("Bundled index does not reference a JavaScript asset")
    if not any(path.endswith(".css") for path in asset_paths):
        raise AssertionError("Bundled index does not reference a CSS asset")

    for path in asset_paths:
        asset = fetch(base_url, path, authorization)
        stripped = asset.body.lstrip().lower()
        if stripped.startswith((b"<!doctype html", b"<html")):
            raise AssertionError(f"{path} was incorrectly served as index.html")
        if path.endswith(".js") and asset.content_type not in {
            "application/javascript",
            "text/javascript",
        }:
            raise AssertionError(
                f"Unexpected JavaScript MIME type for {path}: {asset.content_type}"
            )
        if path.endswith(".css") and asset.content_type != "text/css":
            raise AssertionError(f"Unexpected CSS MIME type for {path}: {asset.content_type}")
        if not asset.body:
            raise AssertionError(f"Bundled asset is empty: {path}")
        print(f"verified {path}: {asset.content_type}, {len(asset.body)} bytes")

    try:
        fetch(base_url, "/assets/does-not-exist.js", authorization)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise AssertionError(
                f"Missing asset returned HTTP {error.code}, expected 404"
            ) from error
    else:
        raise AssertionError("A missing asset was incorrectly handled as an SPA route")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--username", default="user")
    parser.add_argument("--password", default="user")
    args = parser.parse_args()

    try:
        verify(args.base_url, args.username, args.password)
    except (AssertionError, OSError, urllib.error.URLError) as error:
        print(f"bundled SPA verification failed: {error}", file=sys.stderr)
        return 1
    print("bundled SPA verification passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
