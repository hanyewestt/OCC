"""Prefix capacity is quota weight, not the number of populated entries."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class EgressTests(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("occ_egress", ROOT / "scripts/cd/egress-prefix-list.py")
        self.egress = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.egress)

    def test_capacity_includes_reserved_prefix_size_and_other_rules(self):
        with self.assertRaises(ValueError):
            self.egress.validate_capacity(1000, 1000, 1)
        with self.assertRaises(ValueError):
            self.egress.validate_capacity(921, 1000, 5)
        self.egress.validate_capacity(921, 1000, 1)

    def test_collapsing_is_deterministic_and_never_reopens_egress(self):
        self.assertEqual(self.egress.collapse(["10.0.1.0/24", "10.0.0.0/24", "10.0.0.0/24"]),
                         ["10.0.0.0/23"])
        with self.assertRaises(ValueError):
            self.egress.collapse(["0.0.0.0/0"])
        with self.assertRaises(ValueError):
            self.egress.collapse(["0.0.0.0/1", "128.0.0.0/1"])

    def test_offline_plan_filters_regions_and_combines_provider_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "sources.json"
            source.write_text(json.dumps({"aws": {"prefixes": [
                {"region": "us-east-1", "ip_prefix": "10.0.0.0/24"},
                {"region": "eu-west-1", "ip_prefix": "10.20.0.0/24"}]},
                "cloudflare": ["10.0.1.0/24"], "google": {"prefixes": []},
                "github": {"api": ["10.2.0.0/24", "2001:db8::/32"]}}), encoding="utf-8")
            result = subprocess.run([sys.executable, str(ROOT / "scripts/cd/egress-prefix-list.py"),
                                     "plan", "--source-file", str(source)],
                                    capture_output=True, text=True, check=True)
            plan = json.loads(result.stdout)
            self.assertEqual(plan["cidrs"], ["10.0.0.0/23", "10.2.0.0/24"])
            self.assertEqual(plan["egressRuleWeight"], 4)


if __name__ == "__main__":
    unittest.main()
