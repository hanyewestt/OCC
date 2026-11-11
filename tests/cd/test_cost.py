"""Cost estimation contract fixtures."""
import importlib.util
import io
import json
from contextlib import redirect_stdout
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
COST = ROOT / "scripts" / "cd" / "cost-estimate.py"

_spec = importlib.util.spec_from_file_location("occ_cost", COST)
cost = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cost)


class CostEstimateTests(unittest.TestCase):
    def estimate_text(self, hours: float) -> str:
        out = io.StringIO()
        with redirect_stdout(out):
            code = cost.main(["--hours", str(hours)])
        self.assertEqual(code, 0)
        return out.getvalue()

    def test_typical_month_includes_all_planned_lines(self):
        text = self.estimate_text(730)
        for required in ("t3.small", "public IPv4", "gp3 root", "gp3 data",
                         "ECR", "S3", "Secrets Manager", "CloudFront"):
            self.assertIn(required, text)
        self.assertIn("no promotional credits", text)

    def test_longest_month_costs_more(self):
        typical = cost.estimate(730)["total"]
        longest = cost.estimate(744)["total"]
        self.assertGreater(longest, typical)

    def test_total_is_the_sum_of_the_lines(self):
        result = cost.estimate(730)
        self.assertAlmostEqual(result["total"], round(sum(result["lines"].values()), 2))

    def test_no_hidden_resize_or_managed_services(self):
        text = json.dumps(cost.estimate(730))
        for forbidden in ("natgateway", "nat gateway", "alb", "rds", "elasticloadbalancing"):
            self.assertNotIn(forbidden, text.lower())

    def test_implausible_hours_rejected(self):
        out = io.StringIO()
        from contextlib import redirect_stderr

        err = io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = cost.main(["--hours", "10000"])
        self.assertEqual(code, 1)


if __name__ == "__main__":
    unittest.main()
