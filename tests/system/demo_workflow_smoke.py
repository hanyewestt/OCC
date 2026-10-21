"""Verify a deterministic demo bunching event is planned through the public edge."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import ssl
import sys
import time
import urllib.error
import urllib.request


def request(base_url: str, path: str, token: str, method: str = "GET") -> tuple[int, dict | None]:
    body = b"{}" if method == "POST" else None
    message = urllib.request.Request(
        base_url + path,
        data=body,
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
        method=method,
    )
    try:
        with urllib.request.urlopen(message, context=ssl._create_unverified_context(), timeout=15) as response:
            text = response.read().decode("utf-8")
            return response.status, json.loads(text) if text else None
    except urllib.error.HTTPError as response:
        text = response.read().decode("utf-8")
        try:
            payload = json.loads(text) if text else None
        except json.JSONDecodeError:
            payload = {"body": text[:500]}
        return response.code, payload


def wait_for(base_url: str, path: str, token: str, predicate, description: str, timeout: int = 90):
    started = time.perf_counter()
    deadline = time.monotonic() + timeout
    last_status: int | None = None
    last_payload: dict | None = None
    while time.monotonic() < deadline:
        last_status, last_payload = request(base_url, path, token)
        if last_status == 200 and predicate(last_payload):
            elapsed_ms = round((time.perf_counter() - started) * 1000)
            print(f"PASS {description} ({elapsed_ms} ms)")
            return last_payload, elapsed_ms
        if last_status not in (200, 204, 503):
            raise AssertionError(f"{description}: unexpected HTTP {last_status}: {last_payload}")
        time.sleep(1)
    raise AssertionError(f"{description}: timed out; last response {last_status}: {last_payload}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="https://127.0.0.1:18443")
    parser.add_argument("--token-dir", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=90)
    parser.add_argument("--result-file", type=Path)
    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")
    admin_token = (args.token_dir / "admin.jwt").read_text(encoding="utf-8").strip()

    started = time.perf_counter()
    status, run = request(base_url, "/api/v1/demo-runs", admin_token, "POST")
    start_response_ms = round((time.perf_counter() - started) * 1000)
    if status != 201 or not isinstance(run, dict) or not run.get("id"):
        raise AssertionError(f"start demo run: expected HTTP 201 and run id, got {status}: {run}")
    run_id = run["id"]
    print(f"PASS public edge starts deterministic demo run ({start_response_ms} ms)")

    path = f"/api/v1/demo-runs/{run_id}"
    completed, simulation_ready_ms = wait_for(
        base_url,
        path,
        admin_token,
        lambda payload: isinstance(payload, dict)
        and payload.get("status") == "REPLAY_COMPLETE"
        and any(
            item.get("classification") == "SIMULATION"
            and item.get("action") == "OPENED"
            and item.get("bunchingEventId")
            for item in payload.get("alerts", [])
        ),
        "Ingestion simulation reaches Disruption and records a bunching event",
        args.timeout,
    )
    alert = next(
        item for item in completed["alerts"]
        if item.get("classification") == "SIMULATION" and item.get("action") == "OPENED"
    )
    recommendation, recommendation_ready_ms = wait_for(
        base_url,
        f"/api/v1/demo-runs/{run_id}/recommendation",
        admin_token,
        lambda payload: isinstance(payload, dict)
        and bool(payload.get("recommendationId"))
        and payload.get("classification") == "SIMULATION"
        and isinstance(payload.get("expectedImpact"), dict),
        "Planning persists a recommendation retrievable through Ingestion",
        args.timeout,
    )
    if recommendation.get("status") not in {"RECOMMENDED", "NO_ACTIONABLE_RECOMMENDATION"}:
        raise AssertionError(f"unexpected recommendation status: {recommendation.get('status')!r}")
    print(
        "PASS workflow IDs: "
        f"event={alert['bunchingEventId']} recommendation={recommendation['recommendationId']}"
    )

    started = time.perf_counter()
    close_status, _ = request(base_url, f"/api/v1/demo-runs/{run_id}/close", admin_token, "POST")
    close_response_ms = round((time.perf_counter() - started) * 1000)
    if close_status != 200:
        raise AssertionError(f"close demo run: expected HTTP 200, got {close_status}")
    print(f"PASS demo run closes and returns Disruption to live-feed selection ({close_response_ms} ms)")
    if args.result_file:
        args.result_file.parent.mkdir(parents=True, exist_ok=True)
        args.result_file.write_text(json.dumps({
            "demoRunId": run_id,
            "bunchingEventId": alert["bunchingEventId"],
            "recommendationId": recommendation["recommendationId"],
            "simulationReadyMs": simulation_ready_ms,
            "recommendationReadyMs": recommendation_ready_ms,
        }), encoding="utf-8")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, OSError, urllib.error.URLError) as error:
        print(f"FAIL {error}", file=sys.stderr)
        raise SystemExit(1)
