"""Verify a real Planning commit survives a lost response and both app restarts."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
COMPOSE_FILES = (
    "compose.yaml",
    "infra/compose.test.yaml",
    "infra/compose.workflow-test.yaml",
    "infra/compose.dispatch-test.yaml",
)


def compose(project: str, *arguments: str, timeout: int = 30) -> str:
    command = ["docker", "compose", "-p", project, "--env-file", ".env.example"]
    for compose_file in COMPOSE_FILES:
        command.extend(("-f", compose_file))
    result = subprocess.run(command + list(arguments), cwd=ROOT, capture_output=True,
                            text=True, timeout=timeout, check=False)
    if result.returncode:
        detail = (result.stderr or result.stdout).strip()
        raise RuntimeError(f"Compose {' '.join(arguments[:2])} failed ({result.returncode}): {detail[-2000:]}")
    return result.stdout.strip()


def request(base_url: str, path: str, token: str | None = None,
            method: str = "GET") -> tuple[int, dict | None]:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    body = b"{}" if method == "POST" else None
    message = urllib.request.Request(base_url + path, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(message, context=ssl._create_unverified_context(), timeout=15) as response:
            text = response.read().decode("utf-8")
            return response.status, json.loads(text) if text else None
    except urllib.error.HTTPError as response:
        text = response.read().decode("utf-8")
        try:
            return response.code, json.loads(text) if text else None
        except json.JSONDecodeError:
            return response.code, {"body": text[:500]}


def proxy_status(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=5) as response:
        if response.status != 200:
            raise RuntimeError(f"fault-proxy status returned HTTP {response.status}")
        return json.loads(response.read().decode("utf-8"))


def wait_until(description: str, predicate, timeout: int, interval: float = 1.0):
    deadline = time.monotonic() + timeout
    last = None
    last_error: OSError | json.JSONDecodeError | None = None
    while time.monotonic() < deadline:
        try:
            last = predicate()
            last_error = None
            if last:
                return last
        except (OSError, urllib.error.URLError, json.JSONDecodeError) as error:
            last_error = error
        time.sleep(interval)
    detail = f"; last transient error: {type(last_error).__name__}: {last_error}" if last_error else ""
    raise AssertionError(f"Timed out waiting for {description}; last observed value: {last}{detail}")


def database_state(project: str, request_id: uuid.UUID) -> tuple[str, int, int, int]:
    key = str(request_id)
    query = f"""
        SELECT COALESCE((SELECT status FROM disruption.planning_outbox WHERE id = '{key}'::uuid), 'MISSING')
            || '|' || COALESCE((SELECT attempt_count::text FROM disruption.planning_outbox WHERE id = '{key}'::uuid), '-1')
            || '|' || (SELECT count(*)::text FROM planning.planning_request WHERE id = '{key}'::uuid)
            || '|' || (SELECT count(*)::text FROM planning.planning_request
                        WHERE event_id = (SELECT event_id FROM disruption.planning_outbox WHERE id = '{key}'::uuid)
                          AND producer = 'disruption')
    """
    value = compose(project, "exec", "-T", "postgres", "psql", "--no-psqlrc", "--username=occ_admin",
                    "--dbname=occ", "--set=ON_ERROR_STOP=1", "--tuples-only", "--no-align",
                    f"--command={query}", timeout=20)
    fields = value.split("|")
    if len(fields) != 4:
        raise AssertionError(f"Database recovery query returned an unexpected result: {value!r}")
    return fields[0], int(fields[1]), int(fields[2]), int(fields[3])


def wait_healthy(project: str, service: str, timeout: int = 75) -> None:
    container = compose(project, "ps", "-q", service).strip()
    if not container:
        raise AssertionError(f"Compose did not create the {service} container")

    def health():
        result = subprocess.run(["docker", "inspect", "--format", "{{.State.Health.Status}}", container],
                                cwd=ROOT, capture_output=True, text=True, timeout=10, check=False)
        return result.stdout.strip() if result.returncode == 0 else "missing"

    wait_until(f"{service} health after restart", lambda: health() == "healthy", timeout, 2.0)
    print(f"PASS {service} returns healthy after restart")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="https://127.0.0.1:18443")
    parser.add_argument("--token-dir", type=Path, required=True)
    parser.add_argument("--compose-project", required=True)
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")
    token = (args.token_dir / "admin.jwt").read_text(encoding="utf-8").strip()

    port = compose(args.compose_project, "port", "planning-fault-proxy", "8081")
    host, separator, raw_port = port.rpartition(":")
    if not separator or host != "127.0.0.1":
        raise AssertionError(f"fault-proxy control port must be loopback-only, got {port!r}")
    control_url = f"http://127.0.0.1:{raw_port}/__test/status"
    wait_until("fault-proxy control endpoint", lambda: proxy_status(control_url), 30, 0.5)

    status, run = request(base_url, "/api/v1/demo-runs", token, "POST")
    if status != 201 or not isinstance(run, dict) or not run.get("id"):
        raise AssertionError(f"start demo run: expected HTTP 201 and run id, got {status}: {run}")
    run_id = run["id"]
    print("PASS Gateway starts a deterministic simulation run")

    run_path = f"/api/v1/demo-runs/{run_id}"
    completed = wait_until(
        "simulation event creation",
        lambda: (lambda result: result[1] if result[0] == 200 and isinstance(result[1], dict)
                 and result[1].get("status") == "REPLAY_COMPLETE"
                 and any(item.get("classification") == "SIMULATION" and item.get("action") == "OPENED"
                         and item.get("bunchingEventId") for item in result[1].get("alerts", [])) else None)(
                             request(base_url, run_path, token)),
        args.timeout,
    )
    event = next(item for item in completed["alerts"]
                 if item.get("classification") == "SIMULATION" and item.get("action") == "OPENED")
    print("PASS Ingestion and Disruption persist one simulation bunching event")

    first = wait_until(
        "Planning commit followed by a dropped first response",
        lambda: (lambda current: current if current.get("firstReceiverStatus") == 202
                 and current.get("firstResponseDropped") else None)(proxy_status(control_url)),
        args.timeout,
        0.5,
    )
    request_id = uuid.UUID(first["idempotencyKey"])
    if first["calls"] != 1:
        raise AssertionError(f"expected one initial Planning delivery before restart, got {first['calls']}")
    if database_state(args.compose_project, request_id) != ("PENDING", 1, 1, 1):
        raise AssertionError("Planning must have one committed row while Disruption retains one retryable outbox row")
    print("PASS Planning committed one request before its HTTP 202 response was dropped")

    compose(args.compose_project, "restart", "planning", "disruption", timeout=45)
    wait_healthy(args.compose_project, "planning")
    wait_healthy(args.compose_project, "disruption")
    state_after_restart = database_state(args.compose_project, request_id)
    if state_after_restart not in {
        ("PENDING", 1, 1, 1),
        ("DELIVERED", 2, 1, 1),
    }:
        raise AssertionError(
            "receiver/outbox state changed unexpectedly across the two service restarts: "
            f"{state_after_restart}"
        )
    print("PASS both services restart with the receiver row and retryable outbox intact")

    def delivered():
        state = proxy_status(control_url)
        rows = database_state(args.compose_project, request_id)
        if state.get("calls") >= 2 and rows[0] == "DELIVERED":
            return state, rows
        return None

    replay, rows = wait_until("idempotent retry and outbox delivery", delivered, args.timeout, 1.0)
    if not (replay.get("firstResponseDropped") and replay.get("sameIdempotencyKey")
            and replay.get("sameFingerprint") and replay.get("samePayload")
            and replay.get("lastResponseStatus") == 202 and replay.get("calls") == 2):
        raise AssertionError(f"retry did not preserve the original submission: {replay}")
    if rows != ("DELIVERED", 2, 1, 1):
        raise AssertionError(f"expected one delivered outbox and one Planning request, got {rows}")
    if event.get("bunchingEventId") is None:
        raise AssertionError("the completed simulation is missing its stable event identity")
    print("PASS retry reuses the same key, fingerprint, payload and single durable Planning request")

    recommendation_path = f"{run_path}/recommendation"
    recommendation = wait_until(
        "queryable recommendation after restart recovery",
        lambda: (lambda result: result[1] if result[0] == 200 and isinstance(result[1], dict)
                 and result[1].get("recommendationId") and isinstance(result[1].get("expectedImpact"), dict)
                 else None)(request(base_url, recommendation_path, token)),
        args.timeout,
    )
    if recommendation.get("status") not in {"RECOMMENDED", "NO_ACTIONABLE_RECOMMENDATION"}:
        raise AssertionError(f"unexpected recovered recommendation status: {recommendation.get('status')!r}")
    print("PASS recovered recommendation is queryable through the existing Gateway workflow")

    close_status, _ = request(base_url, f"{run_path}/close", token, "POST")
    if close_status != 200:
        raise AssertionError(f"close demo run: expected HTTP 200, got {close_status}")
    print("PASS simulation closes after recovery")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, OSError, RuntimeError, urllib.error.URLError,
            subprocess.SubprocessError, ValueError) as error:
        print(f"FAIL {error}", file=sys.stderr)
        raise SystemExit(1)
