#!/usr/bin/env python3
"""Compare running service OpenAPI output with the committed contracts."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import time
from typing import Any
from urllib.parse import urlsplit
from unittest.mock import patch


SERVICES = {
    "gateway": Path("services/gateway/openapi/gateway.json"),
    "ingestion": Path("services/ingestion/openapi/ingestion.json"),
    "disruption": Path("services/disruption/openapi/disruption.json"),
    "planning": Path("services/planning/openapi/planning.json"),
}
COMPOSE_FILES = (
    "compose.yaml",
    "infra/compose.test.yaml",
    "infra/compose.workflow-test.yaml",
    "infra/compose.dispatch-test.yaml",
)


def first_difference(expected: Any, actual: Any, path: str = "$") -> str | None:
    if type(expected) is not type(actual):
        return path
    if isinstance(expected, dict):
        for key in sorted(expected.keys() - actual.keys()):
            pointer = str(key).replace("~", "~0").replace("/", "~1")
            return f"{path}/{pointer} (missing from live contract)"
        for key in sorted(actual.keys() - expected.keys()):
            pointer = str(key).replace("~", "~0").replace("/", "~1")
            return f"{path}/{pointer} (missing from committed contract)"
        for key in sorted(expected):
            pointer = str(key).replace("~", "~0").replace("/", "~1")
            difference = first_difference(expected[key], actual[key], f"{path}/{pointer}")
            if difference:
                return difference
        return None
    if isinstance(expected, list):
        if len(expected) != len(actual):
            return f"{path} (array length differs)"
        for index, (expected_item, actual_item) in enumerate(zip(expected, actual)):
            difference = first_difference(expected_item, actual_item, f"{path}/{index}")
            if difference:
                return difference
        return None
    return None if expected == actual else path


def normalize_response_required_fields(contract: dict[str, Any]) -> dict[str, Any]:
    normalized = json.loads(json.dumps(contract))
    schemas = normalized.get("components", {}).get("schemas", {})
    if not isinstance(schemas, dict):
        return normalized
    for schema_name, schema in schemas.items():
        if schema_name.endswith("Response") and isinstance(schema, dict):
            schema.pop("required", None)
    return normalized


def compose_base(project: str, env_file: Path) -> list[str]:
    command = ["docker", "compose", "--project-name", project, "--env-file", str(env_file)]
    for compose_file in COMPOSE_FILES:
        command.extend(("-f", compose_file))
    return command


def self_test() -> int:
    spec = {
        "openapi": "3.1.0",
        "servers": [{"url": "http://localhost:6767"}],
        "paths": {"/api/v1/ingestion/demo-runs": {"post": {"operationId": "startDemoRun"}}},
    }
    if first_difference(spec, json.loads(json.dumps(spec))) is not None:
        return 1
    changed = json.loads(json.dumps(spec))
    del changed["paths"]["/api/v1/ingestion/demo-runs"]
    if first_difference(spec, changed) != "$/paths/~1api~1v1~1ingestion~1demo-runs (missing from live contract)":
        return 1
    response_required_expected = {
        "components": {
            "schemas": {
                "DemoResponse": {
                    "type": "object",
                    "properties": {"value": {"type": "string"}},
                    "required": ["value"],
                }
            }
        }
    }
    response_required_live = {
        "components": {
            "schemas": {"DemoResponse": {"type": "object", "properties": {"value": {"type": "string"}}}}
        }
    }
    if (
        first_difference(
            normalize_response_required_fields(response_required_expected),
            normalize_response_required_fields(response_required_live),
        )
        is not None
    ):
        return 1
    request_required_expected = {
        "components": {
            "schemas": {
                "DemoRequest": {
                    "type": "object",
                    "properties": {"value": {"type": "string"}},
                    "required": ["value"],
                }
            }
        }
    }
    request_required_live = {
        "components": {
            "schemas": {"DemoRequest": {"type": "object", "properties": {"value": {"type": "string"}}}}
        }
    }
    if (
        first_difference(
            normalize_response_required_fields(request_required_expected),
            normalize_response_required_fields(request_required_live),
        )
        != "$/components/schemas/DemoRequest/required (missing from live contract)"
    ):
        return 1
    for server_url, expected_host in (
        ("/", None),
        ("/occ", None),
        ("http://localhost:6767", "localhost:6767"),
        ("https://occ.example.com", "occ.example.com"),
    ):
        contract = {**spec, "servers": [{"url": server_url}]}
        response = subprocess.CompletedProcess([], 0, stdout=json.dumps(contract))
        with patch("subprocess.run", return_value=response) as request:
            live = fetch_contract(
                Path.cwd(), ["docker", "compose"], "synthetic", "gateway", contract, "8004"
            )
        if first_difference(contract, live) is not None or request.call_count != 1:
            return 1
        command = request.call_args.args[0]
        hosts = [argument for argument in command if argument.startswith("Host: ")]
        if hosts != ([] if expected_host is None else [f"Host: {expected_host}"]):
            return 1
        if command[-1] != "http://127.0.0.1:8004/v3/api-docs":
            return 1
    print("OpenAPI comparison self-test passed: contract differences detected; "
          "relative and absolute server URLs supported")
    return 0


def fetch_contract(
    root: Path,
    compose: list[str],
    token: str,
    service: str,
    expected: dict[str, Any],
    internal_port: str,
) -> dict[str, Any]:
    servers = expected.get("servers") or []
    if len(servers) != 1 or not isinstance(servers[0].get("url"), str):
        raise SystemExit(f"{service}: committed contract must have exactly one server URL")
    server = urlsplit(servers[0]["url"])
    if server.scheme and not server.netloc:
        raise SystemExit(f"{service}: committed server URL has no host")
    url = f"http://127.0.0.1:{internal_port}/v3/api-docs"
    command = compose + [
        "exec",
        "--no-TTY",
        "--env",
        f"OCC_OPENAPI_EXPORT_TOKEN={token}",
        service,
        "curl",
        "--fail",
        "--silent",
        "--show-error",
        "--header",
        f"Authorization: Bearer {token}",
    ]
    # Absolute generated server URLs depend on Host; relative URLs use curl's
    # normal request host and remain valid behind any deployment's Nginx edge.
    if server.netloc:
        command.extend(("--header", f"Host: {server.netloc}"))
    command.append(url)
    last_status = "no response"
    for _ in range(30):
        response = subprocess.run(
            command, cwd=root, capture_output=True, text=True, encoding="utf-8", check=False
        )
        if response.returncode == 0:
            try:
                return json.loads(response.stdout)
            except json.JSONDecodeError:
                raise SystemExit(f"{service}: live OpenAPI endpoint returned invalid JSON") from None
        last_status = f"exit {response.returncode}"
        time.sleep(2)
    raise SystemExit(f"{service}: OpenAPI endpoint did not become available ({last_status})")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compose-project", default="occ-edge-ci")
    parser.add_argument("--env-file", type=Path, default=Path(".env.example"))
    parser.add_argument("--token-file", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if args.token_file is None:
        parser.error("--token-file is required unless --self-test is used")

    root = Path(__file__).resolve().parents[2]
    token_path = args.token_file if args.token_file.is_absolute() else root / args.token_file
    token = token_path.read_text(encoding="utf-8").strip()
    if not token:
        raise SystemExit("OpenAPI export token is empty")
    env_file = args.env_file if args.env_file.is_absolute() else root / args.env_file
    compose = compose_base(args.compose_project, env_file)
    config = subprocess.run(
        compose + ["config", "--format", "json"],
        cwd=root,
        capture_output=True,
        text=True,
        encoding="utf-8",
        check=False,
    )
    if config.returncode != 0:
        raise SystemExit("Unable to resolve the test Compose configuration")
    services = json.loads(config.stdout)["services"]

    for service, contract_path in SERVICES.items():
        expected = json.loads((root / contract_path).read_text(encoding="utf-8"))
        internal_port = str(services[service]["environment"][f"OCC_{service.upper()}_INTERNAL_PORT"])
        live = fetch_contract(root, compose, token, service, expected, internal_port)
        difference = first_difference(
            normalize_response_required_fields(expected), normalize_response_required_fields(live)
        )
        if difference:
            raise SystemExit(f"{service}: live OpenAPI differs from {contract_path} at {difference}")
        print(f"{service}: live OpenAPI matches {contract_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
