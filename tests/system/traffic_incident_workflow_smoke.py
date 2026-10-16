"""Verify DataMall traffic incidents flow through Ingestion, Disruption and Planning."""

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


COMPOSE_FILES = (
    "compose.yaml",
    "infra/compose.test.yaml",
    "infra/compose.workflow-test.yaml",
    "infra/compose.dispatch-test.yaml",
)
EXPECTED_MESSAGE = "SYNTHETIC: road works near test junction"


def request(base_url: str, path: str, token: str) -> tuple[int, dict | None]:
    message = urllib.request.Request(base_url + path,
        headers={"Authorization": f"Bearer {token}"}, method="GET")
    try:
        with urllib.request.urlopen(message, context=ssl._create_unverified_context(), timeout=15) as response:
            text = response.read().decode("utf-8")
            return response.status, json.loads(text) if text else None
    except urllib.error.HTTPError as response:
        response.read()
        return response.code, None


def compose_query(project: str, query: str) -> str:
    command = ["docker", "compose", "-p", project, "--env-file", ".env.example"]
    for compose_file in COMPOSE_FILES:
        command.extend(("-f", compose_file))
    command.extend(("exec", "-T", "postgres", "psql", "--no-psqlrc", "--username=occ_admin",
                    "--dbname=occ", "--set=ON_ERROR_STOP=1", "--tuples-only", "--no-align",
                    f"--command={query}"))
    result = subprocess.run(command, capture_output=True, text=True, timeout=20, check=False)
    if result.returncode:
        raise RuntimeError("Could not query the disposable workflow database")
    return result.stdout.strip()


def wait_for(predicate, description: str, timeout: int):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = predicate()
        if result:
            print(f"PASS {description}")
            return result
        time.sleep(1)
    raise AssertionError(f"Timed out waiting for {description}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="https://127.0.0.1:18443")
    parser.add_argument("--token-file", type=Path, required=True)
    parser.add_argument("--compose-project", required=True)
    parser.add_argument("--timeout", type=int, default=90)
    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")
    admin_token = args.token_file.read_text(encoding="utf-8").strip()

    def active_incident():
        status, payload = request(base_url, "/api/v1/disruption/traffic-incidents?page=0&size=100", admin_token)
        if status != 200:
            if status in (401, 403, 404, 503):
                return None
            raise AssertionError(f"Traffic incident API returned unexpected HTTP {status}")
        items = payload.get("items", []) if isinstance(payload, dict) else []
        return next((item for item in items if item.get("classification") == "OBSERVATION_ONLY"
                     and item.get("incidentType") == "Road Works"
                     and item.get("message") == EXPECTED_MESSAGE), None)

    incident = wait_for(active_incident, "live-provider observation is active through Gateway", args.timeout)
    if incident.get("demoRunId") is not None:
        raise AssertionError("provider observation was incorrectly associated with a demo run")

    query = """
        SELECT
          (SELECT count(*) FROM ingestion.poll_run
             WHERE source='TRAFFIC_DATAMALL' AND outcome='SUCCESS')::text || '|' ||
          (SELECT count(*) FROM ingestion.traffic_incident_observation
             WHERE classification='OBSERVATION_ONLY' AND message='SYNTHETIC: road works near test junction')::text || '|' ||
          (SELECT count(*) FROM planning.planning_request
             WHERE event_type='TRAFFIC_INCIDENT' AND producer='disruption' AND status='COMPLETED'
               AND policy_outcome='TRAFFIC_INCIDENT_NO_OPERATIONAL_ACTION')::text
    """

    def completed_pipeline():
        values = compose_query(args.compose_project, query).split("|")
        if len(values) != 3:
            raise AssertionError("Workflow database returned an unexpected verification result")
        counts = tuple(int(value) for value in values)
        return counts if all(value > 0 for value in counts) else None

    counts = wait_for(completed_pipeline,
        "raw/normalized observation and Planning no-action policy are persisted", args.timeout)
    print(f"PASS provider incidents={counts[1]}, completed no-action requests={counts[2]}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, OSError, RuntimeError, subprocess.SubprocessError,
            urllib.error.URLError) as error:
        print(f"FAIL {error}", file=sys.stderr)
        raise SystemExit(1)
