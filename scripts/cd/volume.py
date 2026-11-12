#!/usr/bin/env python3
"""Resolve an attached Nitro EBS volume by identity, never by enumeration order."""
from pathlib import Path
import argparse
import re
import sys


def resolve_device(volume_id: str, sys_block: Path = Path("/sys/block")) -> Path:
    if not re.fullmatch(r"vol-[0-9a-f]{8,17}", volume_id):
        raise ValueError("expected a valid EBS volume ID")
    expected = volume_id.replace("-", "")
    matches = []
    for path in sys_block.glob("nvme*n1/device/serial"):
        if path.read_text(encoding="ascii").strip().replace("-", "") == expected:
            matches.append(Path("/dev") / path.parents[1].name)
    if len(matches) != 1:
        raise ValueError("expected exactly one attached device for the designated EBS volume")
    return matches[0]


def initialization_required(kind: str, actual: str, expected: str, initialize: bool) -> bool:
    if kind:
        if not expected or actual != expected:
            raise ValueError("existing filesystem does not match the independently verified UUID")
        return False
    if actual or expected or not initialize:
        raise ValueError("only an explicitly designated new blank volume may be initialized")
    return True


if __name__ == "__main__":
    try:
        parser = argparse.ArgumentParser(description=__doc__)
        commands = parser.add_subparsers(dest="command", required=True)
        commands.add_parser("resolve").add_argument("volume_id")
        check = commands.add_parser("check")
        for name in ("kind", "actual", "expected", "initialize"):
            check.add_argument(name)
        args = parser.parse_args()
        if args.command == "resolve":
            print(resolve_device(args.volume_id))
        else:
            print("initialize" if initialization_required(args.kind, args.actual, args.expected,
                                                         args.initialize == "true") else "adopt")
    except (ValueError, OSError) as error:
        sys.exit(f"volume: {error}")
