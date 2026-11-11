#!/usr/bin/env python3
"""Versioned S3 state and immutable release reads shared by CD/control jobs."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


class StateError(RuntimeError):
    def __init__(self, code: str, message: str):
        super().__init__(f"{code}: {message}")
        self.code = code


class ConflictError(StateError):
    """A competing writer changed the object; callers must reselect/reconcile."""


def aws(arguments: list[str], region: str) -> dict:
    result = subprocess.run([shutil.which("aws") or "aws", "--region", region, *arguments],
                            capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode:
        match = re.search(r"\(([^)]+)\)", result.stderr)
        code = match.group(1) if match else "AwsRequestFailed"
        error = ConflictError if code in ("PreconditionFailed", "ConditionalRequestConflict", "412", "409") else StateError
        raise error(code, f"{arguments[0]} {arguments[1]} failed; inspect AWS permissions/response")
    try:
        return json.loads(result.stdout or "{}")
    except json.JSONDecodeError as error:
        raise StateError("InvalidResponse", "AWS returned invalid metadata") from error


def read_json(bucket: str, key: str, region: str, *, version_id: str | None = None) -> dict:
    with tempfile.TemporaryDirectory(prefix="occ-state-read-") as directory:
        path = Path(directory) / "body.json"
        arguments = ["s3api", "get-object", "--bucket", bucket, "--key", key]
        if version_id:
            arguments += ["--version-id", version_id]
        try:
            metadata = aws([*arguments, str(path)], region)
        except StateError as error:
            if not version_id and error.code in ("NoSuchKey", "NotFound", "404"):
                return {"value": None, "etag": None, "versionId": None, "checksum": None}
            raise
        raw = path.read_bytes()
        try:
            value = json.loads(raw)
        except (json.JSONDecodeError, UnicodeDecodeError) as error:
            raise StateError("InvalidState", f"{key} is not valid JSON") from error
        if not isinstance(value, dict):
            raise StateError("InvalidState", f"{key} must be an object")
        return {"value": value, "etag": metadata.get("ETag"),
                "versionId": metadata.get("VersionId"),
                "checksum": "sha256:" + hashlib.sha256(raw).hexdigest()}


def write_immutable(bucket: str, key: str, content: bytes, region: str) -> dict:
    return _put(bucket, key, content, None, region)


def compare_and_set_json(bucket: str, key: str, value: dict,
                         expected_etag: str | None, region: str) -> dict:
    content = json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return _put(bucket, key, content, expected_etag, region)


def _put(bucket: str, key: str, content: bytes, expected_etag: str | None, region: str) -> dict:
    with tempfile.TemporaryDirectory(prefix="occ-state-write-") as directory:
        path = Path(directory) / "body"
        path.write_bytes(content)
        arguments = ["s3api", "put-object", "--bucket", bucket, "--key", key, "--body", str(path)]
        arguments += ["--if-match", expected_etag] if expected_etag else ["--if-none-match", "*"]
        metadata = aws(arguments, region)
    if not metadata.get("ETag") or metadata.get("VersionId") in (None, "", "-", "null"):
        raise StateError("UnversionedWrite", "state write has no real ETag/VersionId; reconcile accepted write")
    return {"etag": metadata["ETag"], "versionId": metadata["VersionId"],
            "checksum": "sha256:" + hashlib.sha256(content).hexdigest()}


def validate_reference(reference: dict) -> None:
    release_id = reference.get("releaseId", "")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,199}", release_id) or ".." in release_id:
        raise StateError("InvalidReference", "unsafe release ID")
    if reference.get("releaseKey") != f"releases/{release_id}.json":
        raise StateError("InvalidReference", "release key does not match release ID")
    if not re.fullmatch(r"[0-9a-f]{40}", reference.get("revision", "")):
        raise StateError("InvalidReference", "release revision must be a full SHA")
    if reference.get("version") in (None, "", "-", "null"):
        raise StateError("InvalidReference", "a real immutable manifest version is required")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", reference.get("checksum", "")):
        raise StateError("InvalidReference", "a SHA-256 manifest checksum is required")


def verified_manifest(bucket: str, reference: dict, region: str) -> dict:
    validate_reference(reference)
    result = read_json(bucket, reference["releaseKey"], region, version_id=reference["version"])
    if result["checksum"] != reference["checksum"]:
        raise StateError("ChecksumMismatch", "immutable manifest bytes do not match the pointer")
    manifest = result["value"]
    if any(manifest.get(key) != reference[key] for key in ("releaseId", "revision")):
        raise StateError("InvalidReference", "manifest identity does not match the pointer")
    return manifest


def initialize_control(bucket: str, region: str) -> None:
    """Bootstrap pauses only a new environment; never overwrite operator intent."""
    current = read_json(bucket, "state/control.json", region)
    if current["value"] is None:
        compare_and_set_json(bucket, "state/control.json",
                             {"desiredMode": "paused", "phase": "uninitialized", "result": None},
                             None, region)


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Initialize a new environment paused, without overwriting existing control")
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--region", required=True)
    args = parser.parse_args()
    initialize_control(args.bucket, args.region)
