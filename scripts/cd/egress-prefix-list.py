#!/usr/bin/env python3
"""Plan explicit HTTPS egress; create/update only on an explicit subcommand."""
import argparse
import ipaddress
import json
from pathlib import Path
import socket
import subprocess
import sys
import time
import urllib.request

SOURCES = {
    "aws": "https://ip-ranges.amazonaws.com/ip-ranges.json",
    "cloudflare": "https://www.cloudflare.com/ips-v4",
    "google": "https://www.gstatic.com/ipranges/goog.json",
    "github": "https://api.github.com/meta",
}


def collapse(cidrs: list[str]) -> list[str]:
    networks = [ipaddress.ip_network(c, strict=True) for c in cidrs]
    if any(n.prefixlen == 0 for n in networks):
        raise ValueError("unrestricted egress is not allowed")
    result = list(ipaddress.collapse_addresses(n for n in networks if n.version == 4))
    if any(n.prefixlen == 0 for n in result):
        raise ValueError("collapsed ranges would reopen unrestricted egress")
    return [str(n) for n in result]


def validate_capacity(max_entries: int, rules_per_group: int, groups_per_eni: int,
                      other_rules: int = 2, ingress_weight: int = 55) -> None:
    if min(max_entries, rules_per_group, groups_per_eni) < 1:
        raise ValueError("prefix capacity and both actual quotas must be positive")
    if max_entries > 1000 or max_entries + other_rules > rules_per_group:
        raise ValueError("prefix MaxEntries plus explicit rules exceeds the SG rule quota")
    if rules_per_group * groups_per_eni > 1000:
        raise ValueError("rules-per-group × groups-per-ENI quota exceeds 1000; coordinate account-wide changes")
    if ingress_weight > rules_per_group:
        raise ValueError("CloudFront managed-list ingress weight exceeds the SG rule quota")


def aws(region: str, *arguments: str) -> dict:
    result = subprocess.run(["aws", "--region", region, *arguments, "--output", "json"],
                            check=True, capture_output=True, text=True)
    return json.loads(result.stdout or "{}")


def quotas(region: str) -> tuple[int, int]:
    values = {q["QuotaName"].lower(): int(q["Value"]) for q in
              aws(region, "service-quotas", "list-service-quotas", "--service-code", "vpc")["Quotas"]}
    return (values["inbound or outbound rules per security group"],
            values["security groups per network interface"])


def wait_ready(region: str, prefix_id: str, attempts: int = 120) -> dict:
    for _ in range(attempts):
        prefix = aws(region, "ec2", "describe-managed-prefix-lists", "--prefix-list-ids", prefix_id)["PrefixLists"][0]
        status = prefix["State"]
        if status in ("create-complete", "modify-complete", "restore-complete"):
            return prefix
        if status not in ("create-in-progress", "modify-in-progress", "restore-in-progress"):
            raise ValueError(f"prefix list {prefix_id} is not usable: {status}")
        time.sleep(2)
    raise ValueError(f"prefix list {prefix_id} did not finish within the bounded wait")


def collect(source_file: str | None, cidrs: list[str], hosts: list[str]) -> tuple[list[str], dict]:
    if source_file:
        documents = json.loads(Path(source_file).read_text(encoding="utf-8"))
    else:
        documents = {}
        for name, url in SOURCES.items():
            request = urllib.request.Request(url, headers={"User-Agent": "occ-egress-preflight"})
            with urllib.request.urlopen(request, timeout=30) as response:
                raw = response.read().decode("utf-8")
            documents[name] = raw.splitlines() if name == "cloudflare" else json.loads(raw)
    ranges = list(cidrs)
    ranges.extend(documents.get("cidrs", []))
    ranges.extend(p["ip_prefix"] for p in documents.get("aws", {}).get("prefixes", [])
                  if p.get("region") in ("us-east-1", "GLOBAL"))
    ranges.extend(documents.get("cloudflare", []))
    ranges.extend(p["ipv4Prefix"] for p in documents.get("google", {}).get("prefixes", [])
                  if "ipv4Prefix" in p)
    github = documents.get("github", {})
    # Hosted Actions runners are outside the EC2 host's network. Their Azure
    # ranges alone exceed SG capacity and do not permit release asset downloads.
    for key in ("web", "api", "git", "packages"):
        ranges.extend(c for c in github.get(key, []) if ":" not in c)
    observed = {}
    for host in hosts:
        addresses = sorted({r[4][0] for r in socket.getaddrinfo(host, 443, socket.AF_INET)})
        observed[host] = addresses
        ranges.extend(f"{address}/32" for address in addresses)
    return collapse(ranges), {"sources": list(documents), "observedProviderAddresses": observed,
                              "warning": "DNS observations need refresh; they are not permanent provider ranges"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("plan", "create", "update", "validate"))
    parser.add_argument("--region", default="us-east-1", choices=("us-east-1",))
    parser.add_argument("--source-file")
    parser.add_argument("--cidr", action="append", default=[])
    parser.add_argument("--provider-host", action="append", default=[])
    parser.add_argument("--prefix-list-id")
    parser.add_argument("--max-entries", type=int)
    parser.add_argument("--rules-per-group", type=int)
    parser.add_argument("--groups-per-eni", type=int)
    parser.add_argument("--ingress-weight", type=int, default=55)
    args = parser.parse_args()
    try:
        if args.command == "validate":
            validate_capacity(args.max_entries or 0, args.rules_per_group or 0,
                              args.groups_per_eni or 0, ingress_weight=args.ingress_weight)
            print("egress quota preflight passed")
            return 0
        cidrs, audit = collect(args.source_file, args.cidr, args.provider_host)
        plan = {"maxEntries": len(cidrs), "egressRuleWeight": len(cidrs) + 2,
                "cidrs": cidrs, **audit}
        if args.command == "plan":
            print(json.dumps(plan, indent=2))
            return 0
        rules, groups = quotas(args.region)
        validate_capacity(len(cidrs), rules, groups, ingress_weight=args.ingress_weight)
        if args.command == "create":
            response = aws(args.region, "ec2", "create-managed-prefix-list",
                           "--prefix-list-name", "occ-ec2-cd-egress", "--address-family", "IPv4",
                           "--max-entries", str(len(cidrs)), "--entries",
                           json.dumps([{"Cidr": c, "Description": "Reviewed OCC HTTPS destination"} for c in cidrs[:100]]))
            prefix = wait_ready(args.region, response["PrefixList"]["PrefixListId"])
            remaining = cidrs[100:]
        else:
            if not args.prefix_list_id:
                raise ValueError("update requires --prefix-list-id")
            prefix = wait_ready(args.region, args.prefix_list_id)
            # Do not shrink/expand capacity while referenced without an explicit reviewed resize.
            validate_capacity(prefix["MaxEntries"], rules, groups, ingress_weight=args.ingress_weight)
            if len(cidrs) > prefix["MaxEntries"]:
                raise ValueError("new ranges exceed current capacity; review a prefix resize first")
            old = {e["Cidr"] for e in aws(args.region, "ec2", "get-managed-prefix-list-entries",
                                        "--prefix-list-id", prefix["PrefixListId"])["Entries"]}
            if old - set(cidrs):
                raise ValueError("update would remove ranges; review removals explicitly to avoid breaking a running host")
            remaining = [c for c in cidrs if c not in old]
        for start in range(0, len(remaining), 100):
            response = aws(args.region, "ec2", "modify-managed-prefix-list", "--prefix-list-id",
                           prefix["PrefixListId"], "--current-version", str(prefix["Version"]),
                           "--add-entries", json.dumps([{"Cidr": c} for c in remaining[start:start + 100]]))
            prefix = wait_ready(args.region, response["PrefixList"]["PrefixListId"])
        print(json.dumps({"prefixListId": prefix["PrefixListId"], "maxEntries": prefix["MaxEntries"]}))
        return 0
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        print(f"egress: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
