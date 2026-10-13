"""Check Maven test execution without mistaking Docker skips for coverage."""

import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def check_reports(service):
    service = Path(service)
    lines = [f"## Tests: {service.name}"]
    errors = []
    for kind, pattern, folder in (
        ("Unit/controller", "*Test.java", "surefire-reports"),
        ("Integration", "*IT.java", "failsafe-reports"),
    ):
        sources = sorted((service / "src/test/java").rglob(pattern))
        reports = sorted((service / "target" / folder).glob("TEST-*.xml"))
        totals = dict(tests=0, failures=0, errors=0, skipped=0)
        suites = set()
        for report in reports:
            try:
                root = ET.parse(report).getroot()
                suites.add(root.attrib["name"].rsplit(".", 1)[-1])
                for key in totals:
                    totals[key] += int(root.attrib.get(key, 0))
            except (ET.ParseError, KeyError, ValueError) as error:
                errors.append(f"Invalid report {report.name}: {error}")
        lines.append(f"- {kind}: {totals['tests']} tests, "
                     f"{totals['failures']} failures, {totals['errors']} errors, "
                     f"{totals['skipped']} skipped.")
        if sources:
            missing = [p.stem for p in sources if p.stem not in suites]
            if missing:
                errors.append(f"{kind} reports missing for: {', '.join(missing)}")
            if totals["tests"] == 0:
                errors.append(f"No {kind.lower()} tests executed.")
        elif kind == "Integration":
            lines.append("- Coverage gap: this service has no integration test classes.")
        else:
            errors.append("This service has no unit/controller test classes.")
        if totals["failures"] or totals["errors"]:
            errors.append(f"{kind} tests failed.")
        if kind == "Integration" and totals["skipped"]:
            errors.append("Integration tests were skipped; check Docker/Testcontainers.")
    lines.extend(f"- FAILED: {error}" for error in errors)
    return "\n".join(lines) + "\n", errors


if __name__ == "__main__":
    summary, errors = check_reports(sys.argv[1])
    print(summary)
    if path := os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(path, "a", encoding="utf-8") as output:
            output.write(summary)
    sys.exit(1 if errors else 0)
