#!/usr/bin/env python3
"""Parse literal dotenv settings to a private file; never emit setting values."""
import argparse
import json
import os
import re
from dotenv import dotenv_values


def read_settings(path):
    # Credentials containing dollars stay literal; importing is not shell execution.
    values = dotenv_values(path, encoding="utf-8-sig", interpolate=False)
    if any(not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key) for key in values):
        raise ValueError("deployment settings contain an invalid environment variable name")
    return {key: value for key, value in values.items() if value is not None}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    values = read_settings(args.input)
    descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        json.dump(values, handle)
