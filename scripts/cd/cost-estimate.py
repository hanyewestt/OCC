#!/usr/bin/env python
"""Estimate the always-on monthly cost of the OCC EC2 CD deployment.

Deterministic arithmetic over documented us-east-1 price inputs (USD, Linux
shared tenancy, on-demand, no promotional credits). Prices are inputs in this
file with their effective date; refresh them from the AWS pricing pages
before relying on the total. Usage-driven lines (CloudFront, ECR, S3, logs)
use the bounded planning allowances from docs/DEPLOYMENT.md.

Usage: cost-estimate.py [--hours 730] [--json]
"""
from __future__ import annotations

import argparse
import json
import sys

# Price inputs (USD, us-east-1, checked 2026-11-10 against published AWS
# pricing pages; refresh before use):
PRICES = {
    "t3_small_hour_linux": 0.0208,        # on-demand, Linux, shared tenancy
    "public_ipv4_hour": 0.005,            # public IPv4 address charge (2024+)
    "gp3_gb_month": 0.08,                 # EBS gp3 storage, <=3000 IOPS included
    "ecr_gb_month": 0.10,                 # ECR storage
    "s3_standard_gb_month": 0.023,        # S3 Standard first 50 TB
    "s3_requests_per_thousand_puts": 0.005,
    "secret_month": 0.40,                 # Secrets Manager per secret
    "cloudfront_https_gb": 0.085,         # first 10 TB HTTPS North America+EU
}

# Planning allowances (bounded, from docs/DEPLOYMENT.md):
ALLOWANCES = {
    "hours_per_month": 730,               # also run with 744 for longest month
    "root_volume_gb": 20,
    "data_volume_gb": 20,
    "ecr_storage_gb": 10,                 # five repos with bounded tags
    "release_bundle_gb": 5,               # S3 release bundles (versioned)
    "backup_gb": 10,                      # pg_dump backups (versioned)
    "monthly_backup_uploads": 60,         # ~2/day, thousand-put granularity
    "secrets": 2,                         # deployment settings + origin secret
    "cloudfront_transfer_gb": 50,
}


def estimate(hours: float) -> dict:
    lines = {
        "t3.small instance (always on)": round(PRICES["t3_small_hour_linux"] * hours, 2),
        "public IPv4 address": round(PRICES["public_ipv4_hour"] * hours, 2),
        "EBS gp3 root (20 GiB)": round(PRICES["gp3_gb_month"] * ALLOWANCES["root_volume_gb"], 2),
        "EBS gp3 data (20 GiB, retained)": round(
            PRICES["gp3_gb_month"] * ALLOWANCES["data_volume_gb"], 2),
        "ECR storage (10 GiB bound)": round(PRICES["ecr_gb_month"] * ALLOWANCES["ecr_storage_gb"], 2),
        "S3 release bundles + backups (15 GiB bound)": round(
            PRICES["s3_standard_gb_month"]
            * (ALLOWANCES["release_bundle_gb"] + ALLOWANCES["backup_gb"]), 2),
        "S3 backup upload requests": round(
            ALLOWANCES["monthly_backup_uploads"] / 1000
            * PRICES["s3_requests_per_thousand_puts"], 4),
        "Secrets Manager (2 secrets)": round(
            PRICES["secret_month"] * ALLOWANCES["secrets"], 2),
        "CloudFront HTTPS transfer (50 GiB bound)": round(
            PRICES["cloudfront_https_gb"] * ALLOWANCES["cloudfront_transfer_gb"], 2),
    }
    total = round(sum(lines.values()), 2)
    return {"hours": hours, "lines": lines, "total": total}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--hours", type=float, default=ALLOWANCES["hours_per_month"],
                        help="billing hours (730 typical month, 744 longest month)")
    parser.add_argument("--json", action="store_true", help="emit JSON")
    args = parser.parse_args(argv)
    if not 600 <= args.hours <= 750:
        print("error: hours must be within a plausible month (600-750)", file=sys.stderr)
        return 1
    result = estimate(args.hours)
    if args.json:
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    print(f"OCC EC2 CD monthly estimate (us-east-1, Linux on-demand, "
          f"{args.hours:g}h, no promotional credits):")
    for line, value in result["lines"].items():
        print(f"  {line}: ${value:.2f}")
    print(f"  TOTAL: ${result['total']:.2f}")
    print("Excluded: tax, paid providers (Supabase, Gemini), DataMall, data "
          "transfer overages, budget-alert notification costs.")
    print("$50/month is the planning target, not a hard cap; a budget alert "
          "requires a notification destination and does not stop charges.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
