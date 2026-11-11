#!/usr/bin/env python
"""Verify release artifact bundles and stage narrow Docker build contexts.

The CD pipeline packages verified build outputs (three service JARs and the
frontend dist) as run/SHA-bound artifacts with checksums. This tool rejects
incomplete, tampered, stale or wrong-revision bundles and assembles the
minimal build contexts the runtime Dockerfiles consume.

Subcommands:
  verify-bundle     Validate a complete artifact bundle.
  runtime-context   Stage one service JAR as {output}/app.jar.
  web-context       Stage the frontend dist and Nginx templates.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import tarfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

BUNDLE_FILES = ("ingestion.jar", "disruption.jar", "planning.jar", "web-dist.tar.gz")
REVISION_PATTERN = re.compile(r"^[0-9a-f]{40}$")
CHECKSUM_LINE = re.compile(r"^([0-9a-f]{64})  (.+)$")


class StageError(Exception):
    """An artifact bundle or staging contract violation."""


def load_metadata(bundle: Path) -> dict:
    path = bundle / "metadata.json"
    if not path.is_file():
        raise StageError(f"bundle metadata missing: {path}")
    try:
        metadata = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise StageError(f"bundle metadata is not valid JSON: {error}") from error
    if not isinstance(metadata, dict):
        raise StageError("bundle metadata must be a JSON object")
    revision = metadata.get("revision")
    if not isinstance(revision, str) or not REVISION_PATTERN.match(revision):
        raise StageError("bundle metadata has no full 40-hex revision")
    produced_at = metadata.get("producedAt")
    if not isinstance(produced_at, str):
        raise StageError("bundle metadata has no producedAt timestamp")
    try:
        metadata["_produced"] = datetime.strptime(produced_at, "%Y-%m-%dT%H:%M:%SZ").replace(
            tzinfo=timezone.utc)
    except ValueError as error:
        raise StageError(f"bundle producedAt is not an ISO-8601 UTC timestamp: {error}") from error
    return metadata


def verify_checksums(bundle: Path) -> None:
    for name in BUNDLE_FILES:
        artifact = bundle / name
        checksum_file = bundle / f"{name}.sha256"
        if not artifact.is_file() or not checksum_file.is_file():
            raise StageError(f"missing artifact bundle entry: {name}")
        recorded = {}
        for line in checksum_file.read_text(encoding="utf-8").splitlines():
            match = CHECKSUM_LINE.match(line.strip())
            if match:
                recorded[match.group(2)] = match.group(1)
        expected = recorded.get(name)
        if not expected:
            raise StageError(f"checksum file does not cover {name}")
        actual = hashlib.sha256(artifact.read_bytes()).hexdigest()
        if actual != expected:
            raise StageError(f"checksum mismatch for {name}: artifact bytes are not the "
                             f"verified release outputs")


def verify_bundle(bundle: Path, revision: str | None, max_age_hours: float | None) -> dict:
    bundle = Path(bundle)
    if not bundle.is_dir():
        raise StageError(f"bundle directory not found: {bundle}")
    metadata = load_metadata(bundle)
    if revision is not None and metadata["revision"] != revision:
        raise StageError(f"bundle revision {metadata['revision']} does not match the "
                         f"expected release revision {revision}")
    if max_age_hours is not None:
        age = datetime.now(timezone.utc) - metadata["_produced"]
        if age > timedelta(hours=max_age_hours):
            raise StageError(f"bundle produced at {metadata['producedAt']} expired: artifacts "
                             f"older than {max_age_hours}h must not be reused")
    verify_checksums(bundle)
    return metadata


def stage_runtime_context(bundle: Path, service: str, output: Path) -> None:
    if service not in ("ingestion", "disruption", "planning"):
        raise StageError(f"unknown runtime service: {service}")
    verify_bundle(bundle, None, None)
    output = Path(output)
    if output.exists():
        raise StageError(f"staging output already exists: {output}")
    output.mkdir(parents=True)
    source = Path(bundle) / f"{service}.jar"
    (output / "app.jar").write_bytes(source.read_bytes())


# The web image bakes the shared routing include only. Each deployment mode
# supplies its server block: direct TLS mounts default.conf.template, the
# CloudFront overlay mounts cloudfront.conf.template, local development
# mounts local.conf.template over the stock Nginx image.
NGINX_TEMPLATES = ("routes.inc.template",)


def stage_web_context(bundle: Path, output: Path) -> None:
    verify_bundle(bundle, None, None)
    output = Path(output)
    if output.exists():
        raise StageError(f"staging output already exists: {output}")
    output.mkdir(parents=True)
    dist_root = output / "dist"
    dist_root.mkdir()
    with tarfile.open(Path(bundle) / "web-dist.tar.gz", "r:gz") as tar:
        for member in tar.getmembers():
            target = (dist_root / member.name).resolve()
            if not target.is_relative_to(dist_root.resolve()):
                raise StageError(f"dist archive contains an unsafe path: {member.name}")
        tar.extractall(dist_root, filter="data")
    templates = output / "templates"
    templates.mkdir()
    repo_root = Path(__file__).resolve().parents[2]
    for name in NGINX_TEMPLATES:
        source = repo_root / "infra" / "nginx" / name
        if not source.is_file():
            raise StageError(f"missing Nginx template in the repository: {source}")
        (templates / name).write_bytes(source.read_bytes())


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    verify_parser = subparsers.add_parser("verify-bundle", help="validate an artifact bundle")
    verify_parser.add_argument("--bundle", required=True)
    verify_parser.add_argument("--revision", help="expected release revision (40-hex)")
    verify_parser.add_argument("--max-age-hours", type=float,
                               help="reject bundles produced longer ago than this")

    runtime_parser = subparsers.add_parser("runtime-context", help="stage one service JAR")
    runtime_parser.add_argument("--bundle", required=True)
    runtime_parser.add_argument("--service", required=True,
                                choices=["ingestion", "disruption", "planning"])
    runtime_parser.add_argument("--output", required=True)

    web_parser = subparsers.add_parser("web-context", help="stage dist and Nginx templates")
    web_parser.add_argument("--bundle", required=True)
    web_parser.add_argument("--output", required=True)

    args = parser.parse_args(argv)
    try:
        if args.command == "verify-bundle":
            verify_bundle(Path(args.bundle), args.revision, args.max_age_hours)
        elif args.command == "runtime-context":
            stage_runtime_context(Path(args.bundle), args.service, Path(args.output))
        else:
            stage_web_context(Path(args.bundle), Path(args.output))
    except StageError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
