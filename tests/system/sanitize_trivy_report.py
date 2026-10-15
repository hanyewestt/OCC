#!/usr/bin/env python3
"""Remove matched secret values from Trivy reports before artifacts are uploaded."""

from __future__ import annotations

import argparse
import collections
import json
from pathlib import Path
import sys
from typing import Any


def sanitize(report: dict[str, Any]) -> tuple[dict[str, Any], bool]:
    findings: list[dict[str, Any]] = []
    counts: collections.Counter[str] = collections.Counter()
    for result in report.get("Results") or []:
        for section, scanner in (("Secrets", "secret"), ("Misconfigurations", "misconfig")):
            for item in result.get(section) or []:
                severity = str(item.get("Severity", "UNKNOWN")).upper()
                cause = item.get("CauseMetadata") or {}
                counts[severity] += 1
                findings.append(
                    {
                        "target": result.get("Target", ""),
                        "scanner": scanner,
                        "id": item.get("RuleID") or item.get("ID") or "UNKNOWN",
                        "severity": severity,
                        "title": item.get("Title", ""),
                        "startLine": item.get("StartLine") or cause.get("StartLine"),
                        "endLine": item.get("EndLine") or cause.get("EndLine"),
                    }
                )
    summary = {"findings": findings, "counts": dict(sorted(counts.items()))}
    blocked = any(item["severity"] in {"HIGH", "CRITICAL"} for item in findings)
    return summary, blocked


def self_test() -> int:
    marker = "synthetic-mock-secret-value-not-real"
    base = {
        "Results": [
            {
                "Target": "docs/mock-fixture.md",
                "Secrets": [
                    {
                        "RuleID": "TEST_RULE",
                        "Severity": "MEDIUM",
                        "Title": "Synthetic finding",
                        "StartLine": 4,
                        "Match": marker,
                    }
                ],
                "Misconfigurations": [],
            }
        ]
    }
    medium, medium_blocked = sanitize(base)
    high_input = json.loads(json.dumps(base))
    high_input["Results"][0]["Secrets"][0]["Severity"] = "HIGH"
    high, high_blocked = sanitize(high_input)
    serialized = json.dumps({"medium": medium, "high": high})
    if medium_blocked or not high_blocked or marker in serialized:
        return 1
    if any("Match" in item for item in medium["findings"] + high["findings"]):
        return 1
    print("sanitizer self-test passed: matched values omitted; HIGH blocks; MEDIUM passes")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if args.input is None or args.output is None:
        parser.error("--input and --output are required unless --self-test is used")

    try:
        report = json.loads(args.input.read_text(encoding="utf-8"))
        summary, blocked = sanitize(report)
        args.output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    finally:
        args.input.unlink(missing_ok=True)
    print("Repository scan counts (matched values omitted): " + json.dumps(summary["counts"], sort_keys=True))
    if blocked:
        print("Repository secret or misconfiguration findings exceed the allowed severity.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
