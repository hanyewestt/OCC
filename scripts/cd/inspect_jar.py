#!/usr/bin/env python
"""Inspect a packaged service JAR for its required runtime content.

The release pipeline packages verified reactor outputs. Every runtime JAR
must carry the committed OpenAPI contract, the shared Swagger UI page, the
runtime Jackson JavaTime module and the Swagger UI webjar, and must never
ship the test-only springdoc generator.
"""
from __future__ import annotations

import argparse
import json
import sys
import zipfile
from pathlib import Path


class InspectionError(Exception):
    """A packaged JAR content violation."""


def inspect_jar(path: Path, service: str) -> dict:
    if not path.is_file():
        raise InspectionError(f"JAR not found: {path}")
    findings = {}
    try:
        with zipfile.ZipFile(path) as jar:
            names = set(jar.namelist())
            contract = f"BOOT-INF/classes/static/v3/{service}.json"
            if contract not in names:
                raise InspectionError(f"committed contract missing from the JAR: {contract}")
            try:
                json.loads(jar.read(contract).decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                raise InspectionError(f"committed contract is not valid JSON: {contract}: "
                                      f"{error}") from error
            findings[contract] = "present"
            index = "BOOT-INF/classes/static/swagger-ui/index.html"
            if index not in names:
                raise InspectionError(f"shared Swagger UI page missing from the JAR: {index}")
            findings[index] = "present"
            libs = [name for name in names if name.startswith("BOOT-INF/lib/")]
            if not any("jackson-datatype-jsr310" in name for name in libs):
                raise InspectionError("runtime Jackson JavaTime support missing: "
                                      "jackson-datatype-jsr310 must stay on the runtime "
                                      "classpath")
            findings["jackson-jsr310"] = "present"
            if not any(name.startswith("BOOT-INF/lib/swagger-ui-") for name in libs):
                raise InspectionError("Swagger UI webjar missing from runtime dependencies")
            findings["swagger-webjar"] = "present"
            springdoc = [name for name in libs if "springdoc" in name]
            if springdoc:
                raise InspectionError("springdoc must remain test-only; found in the runtime "
                                      f"JAR: {springdoc[0]}")
            findings["springdoc"] = "absent"
    except zipfile.BadZipFile as error:
        raise InspectionError(f"not a readable JAR: {path}: {error}") from error
    return findings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--service", required=True,
                        choices=["ingestion", "disruption", "planning"])
    args = parser.parse_args(argv)
    try:
        findings = inspect_jar(args.jar, args.service)
    except InspectionError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(json.dumps({"jar": str(args.jar), "service": args.service, "findings": findings}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
