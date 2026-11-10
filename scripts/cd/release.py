#!/usr/bin/env python
"""Release input fingerprints, manifests and deployment state contracts.

Pure standard-library CLI for the OCC CD pipeline. It never calls AWS and
never needs credentials: callers supply the published release record and
image digests as JSON files.

Commands:
  plan     Compute build components, deploy requirement and per-component
           input fingerprints for a commit range.
  manifest Validate and emit a schemaVersion=2 release manifest.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

COMPONENTS = ("ingestion", "disruption", "planning", "db-setup", "web")
JAVA_COMPONENTS = ("ingestion", "disruption", "planning")

SCHEMA_VERSION = 2
PLAN_SCHEMA_VERSION = 1

RELEASE_ID_PATTERN = re.compile(r"^[A-Za-z0-9][-A-Za-z0-9_.]{0,127}$")
ECR_DIGEST_PATTERN = re.compile(
    r"^[a-z0-9][a-z0-9-]*\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com/"
    r"[A-Za-z0-9][A-Za-z0-9._/-]*@sha256:[0-9a-f]{64}$")
CREDENTIAL_VALUE_PATTERN = re.compile(r"AKIA[0-9A-Z]{16}")
CREDENTIAL_KEY_PATTERN = re.compile(
    r"(secret_access_key|access_key_id|session_token|password|passwd)", re.IGNORECASE)


class ContractError(Exception):
    """A release contract violation that must fail the pipeline."""


# ---------------------------------------------------------------------------
# Input ownership
# ---------------------------------------------------------------------------

NO_RELEASE_PATHS = frozenset({
    ".env.example", ".gitignore", "compose.override.yaml",
    "infra/compose.test.yaml", "infra/compose.workflow-test.yaml",
    "infra/compose.dispatch-test.yaml",
})
ALL_FIVE = frozenset(COMPONENTS)
JAVA_SET = frozenset(JAVA_COMPONENTS)
DB_SETUP_ONLY = frozenset({"db-setup"})
WEB_ONLY = frozenset({"web"})
NO_RELEASE = frozenset()

WEB_FRONTEND_INPUTS = (
    "frontend/src/",
    "frontend/index.html",
    "frontend/404.html",
    "frontend/package.json",
    "frontend/package-lock.json",
    "frontend/vite.config.ts",
    "frontend/tsconfig.json",
    "frontend/components.json",
)
WEB_NONPRODUCTION = (
    "frontend/test/",
    "frontend/dist/",
    "frontend/eslint.config.js",
    "frontend/playwright.config.ts",
    "frontend/playwright.unit.config.ts",
    "frontend/README.md",
)


def ownership_for(path: str) -> frozenset:
    """Map one changed path to the set of components it invalidates.

    The empty set means the path cannot affect a deployment (docs, tests,
    local launchers). Returns ALL_FIVE for unrecognized paths so unknown
    build or deployment inputs fail conservative instead of silent.
    """
    path = path.replace("\\", "/")
    if not path or path.endswith("/"):
        return ALL_FIVE
    if path == ".dockerignore":
        return ALL_FIVE
    if path.startswith("tests/") or path.startswith("docs/"):
        return NO_RELEASE
    if path in NO_RELEASE_PATHS:
        return NO_RELEASE
    if path.endswith(".md") and "/" not in path:
        return NO_RELEASE
    if path == "LICENSE":
        return NO_RELEASE
    if path.startswith(".github/"):
        return frozenset()  # release-only: pipeline wiring, no packaged bytes
    if path in ("pom.xml", "mvnw", "mvnw.cmd") or path.startswith(".mvn/"):
        return JAVA_SET
    if path == "infra/java.Dockerfile" or path.startswith("infra/swagger-ui/"):
        return JAVA_SET
    if path in ("infra/flyway.Dockerfile", "infra/db-setup.sh") or \
            path.startswith("infra/flyway/") or path.startswith("infra/postgres/"):
        return DB_SETUP_ONLY
    if path == "infra/web.Dockerfile" or path.startswith("infra/nginx/"):
        return WEB_ONLY
    if path.startswith("frontend/"):
        if path.startswith(WEB_NONPRODUCTION):
            return NO_RELEASE
        if path.startswith(WEB_FRONTEND_INPUTS):
            return WEB_ONLY
        return NO_RELEASE
    if path in ("compose.yaml", "infra/compose.production.yaml",
                "infra/compose.images.yaml", "infra/compose.cloudfront.yaml"):
        return frozenset()  # release required, but no image contains these bytes
    if path.startswith("scripts/cd/") or path.startswith("infra/aws/"):
        return frozenset()
    if path.startswith("scripts/"):
        return NO_RELEASE
    if path.startswith("infra/"):
        return NO_RELEASE
    if path.startswith("services/"):
        parts = path.split("/")
        if len(parts) >= 2 and parts[1] in JAVA_COMPONENTS:
            service = parts[1]
            rest = "/".join(parts[2:])
            if rest.startswith("src/test/") or rest.startswith("target/"):
                return NO_RELEASE
            if rest.startswith("src/main/resources/db/"):
                return frozenset({service, "db-setup"})
            if rest.startswith(("src/main/", "openapi/")) or rest == "pom.xml":
                return frozenset({service})
        return ALL_FIVE
    return ALL_FIVE


def classify_paths(paths) -> dict:
    """Classify changed paths into image owners and release requirement."""
    owners: set = set()
    release_affecting = False
    for path in paths:
        component_set = ownership_for(path)
        if component_set is ALL_FIVE:
            owners.update(ALL_FIVE)
            release_affecting = True
        elif component_set:
            owners.update(component_set)
            release_affecting = True
        elif component_set == frozenset() and _is_release_only(path):
            release_affecting = True
    return {"owners": owners, "releaseAffecting": release_affecting}


def _is_release_only(path: str) -> bool:
    path = path.replace("\\", "/")
    if path in ("compose.yaml", "infra/compose.production.yaml",
                "infra/compose.images.yaml", "infra/compose.cloudfront.yaml"):
        return True
    if path.startswith(("scripts/cd/", "infra/aws/", ".github/")):
        return True
    return False


# ---------------------------------------------------------------------------
# Git helpers
# ---------------------------------------------------------------------------

def git(repo: Path, *args: str) -> str:
    result = subprocess.run(["git", *args], cwd=str(repo), capture_output=True,
                            text=True, encoding="utf-8", errors="replace")
    if result.returncode != 0:
        raise ContractError(f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def rev_exists(repo: Path, revision: str) -> bool:
    result = subprocess.run(["git", "cat-file", "-e", f"{revision}^{{commit}}"],
                            cwd=str(repo), capture_output=True)
    return result.returncode == 0


def is_ancestor(repo: Path, ancestor: str, descendant: str) -> bool:
    result = subprocess.run(["git", "merge-base", "--is-ancestor", ancestor, descendant],
                            cwd=str(repo), capture_output=True)
    return result.returncode == 0


def changed_paths(repo: Path, base: str, head: str) -> list[str]:
    out = git(repo, "diff", "--name-only", "--no-renames", "-z", base, head)
    return [entry for entry in out.split("\0") if entry]


def list_tree(repo: Path, revision: str) -> list[tuple[str, str]]:
    out = git(repo, "ls-tree", "-r", "-z", revision)
    entries = []
    for record in out.split("\0"):
        if not record:
            continue
        meta, path = record.split("\t", 1)
        mode, kind, sha = meta.split()
        if kind == "blob":
            entries.append((path, sha))
    return entries


def compute_input_hashes(repo: Path, revision: str) -> dict:
    """Fingerprint the actual packaged inputs of each component at revision."""
    buckets = {component: [] for component in COMPONENTS}
    for path, blob in list_tree(repo, revision):
        owners = ownership_for(path)
        if owners is ALL_FIVE:
            targets = COMPONENTS
        elif owners:
            targets = sorted(owners)
        else:
            continue
        for component in targets:
            buckets[component].append((path, blob))
    hashes = {}
    for component in COMPONENTS:
        hasher = hashlib.sha256()
        for path, blob in sorted(buckets[component]):
            hasher.update(path.encode("utf-8"))
            hasher.update(b"\0")
            hasher.update(blob.encode("ascii"))
            hasher.update(b"\n")
        hashes[component] = hasher.hexdigest()
    return hashes


# ---------------------------------------------------------------------------
# Plan
# ---------------------------------------------------------------------------

def load_json(path: str, label: str) -> dict:
    try:
        payload = json.loads(Path(path).read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise ContractError(f"{label} file not found: {path}") from error
    except json.JSONDecodeError as error:
        raise ContractError(f"{label} file is not valid JSON: {error}") from error
    if not isinstance(payload, dict):
        raise ContractError(f"{label} must be a JSON object")
    return payload


def scan_for_credentials(payload, where: str) -> None:
    if isinstance(payload, dict):
        for key, value in payload.items():
            if CREDENTIAL_KEY_PATTERN.search(str(key)):
                raise ContractError(f"{where} contains a credential-shaped key: {key}")
            scan_for_credentials(value, where)
    elif isinstance(payload, list):
        for item in payload:
            scan_for_credentials(item, where)
    elif isinstance(payload, str):
        if CREDENTIAL_VALUE_PATTERN.search(payload):
            raise ContractError(f"{where} contains a credential-shaped value")


def build_plan(repo: Path, head: str, base: str | None, published: dict | None,
               refresh: bool) -> dict:
    scan_for_credentials(published, "published release record")
    if not rev_exists(repo, head):
        raise ContractError(f"head revision does not exist: {head}")
    if published is not None:
        if "revision" not in published or not published["revision"]:
            raise ContractError("published release record has no revision")
        if "inputHashes" not in published or \
                set(published.get("inputHashes", {})) != set(COMPONENTS):
            raise ContractError("published release record lacks all five input hashes")
        if base is None:
            base = published["revision"]
    if base is None:
        raise ContractError("missing baseline: provide --base or --published")
    if not rev_exists(repo, base):
        raise ContractError(f"baseline revision does not exist: {base}")
    if base != head and not is_ancestor(repo, base, head):
        raise ContractError(
            "divergent history: baseline is not an ancestor of head; refuse for review")

    changed = changed_paths(repo, base, head)
    classification = classify_paths(changed)
    owners = classification["owners"]
    hashes = compute_input_hashes(repo, head)

    published_hashes = (published or {}).get("inputHashes") or {}

    if published:
        same_revision = published["revision"] == head
        if refresh and same_revision:
            build: list[str] = []
        else:
            # Reuse requires identical input hashes; any mismatch rebuilds.
            build = sorted(component for component in COMPONENTS
                           if hashes[component] != published_hashes.get(component))
        deploy_required = classification["releaseAffecting"] or refresh or bool(build)
    else:
        build = sorted(owners)
        deploy_required = classification["releaseAffecting"] or refresh

    return {
        "schemaVersion": PLAN_SCHEMA_VERSION,
        "revision": head,
        "baseRevision": base,
        "buildComponents": build,
        "deployRequired": deploy_required,
        "refresh": bool(refresh),
        "inputHashes": hashes,
    }


# ---------------------------------------------------------------------------
# Manifest
# ---------------------------------------------------------------------------

def validate_release_id(release_id: str) -> None:
    if not release_id or not RELEASE_ID_PATTERN.match(release_id) or \
            ".." in release_id or release_id.endswith("."):
        raise ContractError(
            f"release id must be path-safe (letters, digits, '-', '_', '.', no '..'): "
            f"{release_id!r}")


def validate_digest(component: str, reference: str) -> None:
    if not isinstance(reference, str) or not ECR_DIGEST_PATTERN.match(reference):
        raise ContractError(
            f"image for {component} must be an ECR URI digest-pinned with sha256 "
            f"(<account>.dkr.ecr.<region>.amazonaws.com/<repo>@sha256:<64hex>): {reference!r}")


def build_manifest(plan: dict, previous: dict | None, digests: dict, release_id: str,
                   compatibility: str, evidence: dict | None) -> dict:
    scan_for_credentials(plan, "plan")
    scan_for_credentials(previous, "previous release")
    scan_for_credentials(digests, "digests")
    scan_for_credentials(evidence, "evidence")
    if plan.get("schemaVersion") != PLAN_SCHEMA_VERSION:
        raise ContractError(f"plan schemaVersion must be {PLAN_SCHEMA_VERSION}")
    revision = plan.get("revision")
    if not revision or not isinstance(revision, str) or len(revision) != 40:
        raise ContractError("plan has no full revision SHA")
    input_hashes = plan.get("inputHashes")
    if not isinstance(input_hashes, dict) or set(input_hashes) != set(COMPONENTS):
        raise ContractError("plan must carry all five component input hashes")
    built = plan.get("buildComponents")
    if not isinstance(built, list) or \
            any(component not in COMPONENTS for component in built):
        raise ContractError("plan buildComponents must be a subset of the five components")
    unknown_digest_keys = sorted(set(digests) - set(COMPONENTS))
    if unknown_digest_keys:
        raise ContractError(f"digests contain unknown components: {unknown_digest_keys}")
    validate_release_id(release_id)
    if compatibility not in ("unknown", "backward-compatible"):
        raise ContractError("compatibility must be 'unknown' or 'backward-compatible'")
    if compatibility == "backward-compatible" and evidence is None:
        raise ContractError(
            "backward-compatible requires compatibility evidence (rollbackReleaseId, "
            "setupFingerprint, migrations)")
    if evidence is not None:
        validate_evidence_shape(evidence)

    previous_revision = None
    previous_images = {}
    if previous is not None:
        scan_for_credentials(previous, "previous release")
        previous_revision = previous.get("revision")
        if not previous_revision:
            raise ContractError("previous release record has no revision")
        previous_images = previous.get("images") or {}
        if plan["refresh"] is False and previous_revision == revision:
            raise ContractError(
                "identical revision was already released; use the refresh flow to "
                "publish it under a new release id")
        if previous_revision == revision and plan["refresh"] and \
                previous.get("releaseId") == release_id:
            raise ContractError(
                "a refresh must use a distinct release id; immutable release ids are "
                "never overwritten")

    images = {}
    for component in COMPONENTS:
        if component in built:
            if component not in digests:
                raise ContractError(f"missing digest for built component: {component}")
            reference = digests[component]
        else:
            reference = previous_images.get(component) or digests.get(component)
            if not reference:
                raise ContractError(
                    f"missing digest for reused component {component}: no artifact "
                    f"available, rebuild conservatively")
        validate_digest(component, reference)
        images[component] = reference

    manifest = {
        "schemaVersion": SCHEMA_VERSION,
        "releaseId": release_id,
        "revision": revision,
        "baselineRevision": previous_revision,
        "images": images,
        "inputHashes": input_hashes,
        "builtComponents": sorted(built),
        "dbSetupInputHash": input_hashes["db-setup"],
        "migrationCompatibility": compatibility,
    }
    if evidence is not None:
        manifest["compatibilityEvidence"] = evidence
    return manifest


def validate_manifest(manifest: dict) -> None:
    if manifest.get("schemaVersion") != SCHEMA_VERSION:
        raise ContractError(f"manifest schemaVersion must be {SCHEMA_VERSION}")
    for field in ("releaseId", "revision", "images", "inputHashes", "builtComponents",
                  "dbSetupInputHash", "migrationCompatibility"):
        if field not in manifest:
            raise ContractError(f"manifest is missing required field: {field}")
    validate_release_id(manifest["releaseId"])
    images = manifest["images"]
    if set(images) != set(COMPONENTS):
        raise ContractError(f"manifest images must contain exactly the five components")
    for component, reference in images.items():
        validate_digest(component, reference)
    if set(manifest["inputHashes"]) != set(COMPONENTS):
        raise ContractError("manifest inputHashes must contain exactly the five components")
    if manifest["migrationCompatibility"] not in ("unknown", "backward-compatible"):
        raise ContractError("invalid migrationCompatibility")


def validate_evidence_shape(evidence: dict) -> None:
    required = {"rollbackReleaseId", "setupFingerprint", "migrations"}
    if not isinstance(evidence, dict) or not required.issubset(evidence):
        raise ContractError(
            "compatibility evidence requires rollbackReleaseId, setupFingerprint "
            "and migrations")
    if not isinstance(evidence["rollbackReleaseId"], str) or \
            not evidence["rollbackReleaseId"]:
        raise ContractError("evidence rollbackReleaseId must be a release id string")
    if not isinstance(evidence["setupFingerprint"], str) or not evidence["setupFingerprint"]:
        raise ContractError("evidence setupFingerprint must be a fingerprint string")
    if not isinstance(evidence["migrations"], list) or \
            any(not isinstance(item, str) for item in evidence["migrations"]):
        raise ContractError("evidence migrations must be a list of migration names")


def resolve_migration_compatibility(evidence: dict, last_deployed: dict,
                                    candidate_setup_fingerprint: str,
                                    intervening_migrations: list[str]) -> str:
    """Compare reviewed evidence against the actual last-deployed state.

    Missing or mismatched evidence means 'unknown': rollback authorization can
    never be inferred from SQL file names or candidate build baselines.
    """
    try:
        validate_evidence_shape(evidence)
    except ContractError:
        return "unknown"
    if evidence["rollbackReleaseId"] != last_deployed.get("releaseId"):
        return "unknown"
    if evidence["setupFingerprint"] != last_deployed.get("setupFingerprint"):
        return "unknown"
    if sorted(evidence["migrations"]) != sorted(intervening_migrations):
        return "unknown"
    # A setup-fingerprint change must be explained by the reviewed migration
    # range; an unexplained change (or migrations with an unchanged setup
    # image) means the evidence does not cover the actual candidate state.
    fingerprint_changed = candidate_setup_fingerprint != last_deployed.get("setupFingerprint")
    if fingerprint_changed != bool(intervening_migrations):
        return "unknown"
    return "backward-compatible"


# ---------------------------------------------------------------------------
# Deployment state pointers
# ---------------------------------------------------------------------------

POINTER_FIELDS = ("releaseId", "revision", "releaseKey", "version", "checksum")
CONTROL_MODES = ("running", "paused")
CONTROL_FIELDS = ("desiredMode", "operationId", "phase", "result")


def validate_pointer(pointer: dict) -> None:
    missing = [field for field in POINTER_FIELDS if not pointer.get(field)]
    if missing:
        raise ContractError(f"deployment pointer is missing immutable fields: {missing}")


def reject_older_overwrite(current: dict, candidate: dict,
                           is_ancestor=None) -> None:
    """CAS guard: a pointer may only move forward.

    Rejects equal or lower sequence numbers and, when ancestry can be
    evaluated, candidates whose revision is an ancestor of the current one.
    """
    validate_pointer(current)
    validate_pointer(candidate)
    if candidate["revision"] == current["revision"] and \
            candidate["releaseId"] == current["releaseId"]:
        raise ContractError("identical release pointer cannot overwrite itself")
    if is_ancestor is not None and \
            is_ancestor(candidate["revision"], current["revision"]):
        raise ContractError(
            "candidate pointer revision is an ancestor of the deployed revision; "
            "an older release cannot overwrite a newer pointer")
    current_sequence = current.get("sequence")
    candidate_sequence = candidate.get("sequence")
    if current_sequence is not None and candidate_sequence is not None:
        if candidate_sequence <= current_sequence:
            raise ContractError(
                "candidate pointer sequence is not newer than the current one; "
                "compare-and-set rejects the overwrite")


def validate_control(state: dict) -> None:
    mode = state.get("desiredMode")
    if mode not in CONTROL_MODES:
        raise ContractError(f"desiredMode must be one of {CONTROL_MODES}: {mode!r}")
    unknown = sorted(set(state) - set(CONTROL_FIELDS))
    if unknown:
        raise ContractError(f"control state has unknown fields: {unknown}")
    for field in CONTROL_FIELDS[1:]:
        if field in state and not isinstance(state[field], (str, type(None))):
            raise ContractError(f"control field {field} must be a string or null")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    plan_parser = subparsers.add_parser("plan", help="compute build components and hashes")
    plan_parser.add_argument("--head", required=True)
    plan_parser.add_argument("--base")
    plan_parser.add_argument("--published", help="published release manifest JSON")
    plan_parser.add_argument("--repo", default=".", help="git repository (default: cwd)")
    plan_parser.add_argument("--refresh", action="store_true",
                             help="re-release an unchanged revision under a new id")

    manifest_parser = subparsers.add_parser("manifest", help="validate a release manifest")
    manifest_parser.add_argument("--plan", required=True)
    manifest_parser.add_argument("--previous")
    manifest_parser.add_argument("--digests", required=True)
    manifest_parser.add_argument("--release-id", required=True)
    manifest_parser.add_argument("--compatibility", required=True,
                                 choices=["unknown", "backward-compatible"])
    manifest_parser.add_argument("--evidence")

    args = parser.parse_args(argv)
    try:
        if args.command == "plan":
            published = load_json(args.published, "published") if args.published else None
            plan = build_plan(Path(args.repo), args.head, args.base, published, args.refresh)
            print(json.dumps(plan, indent=2, sort_keys=True))
        else:
            plan = load_json(args.plan, "plan")
            previous = load_json(args.previous, "previous") if args.previous else None
            digests = load_json(args.digests, "digests")
            evidence = load_json(args.evidence, "evidence") if args.evidence else None
            manifest = build_manifest(plan, previous, digests, args.release_id,
                                      args.compatibility, evidence)
            validate_manifest(manifest)
            print(json.dumps(manifest, indent=2, sort_keys=True))
    except ContractError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
