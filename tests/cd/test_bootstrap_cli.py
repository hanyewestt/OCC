"""Execute workstation bootstrap with command-boundary doubles, never AWS/GitHub."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class BootstrapCliTests(unittest.TestCase):
    def test_applied_stack_configures_main_only_environment_and_scoped_waiter(self):
        with tempfile.TemporaryDirectory(prefix="occ-bootstrap-cli-") as temporary:
            root = Path(temporary)
            env_file = root / "fixture.env"
            # Names only from the public inventory; no real .env or credentials.
            keys = [line.split("=", 1)[0] for line in
                    (ROOT / ".env.example").read_text(encoding="utf-8").splitlines()
                    if line and not line.startswith("#") and "=" in line]
            env_file.write_text("\n".join(f"{key}=fixture" for key in keys), encoding="utf-8")
            log = root / "calls.jsonl"
            script = root / "run.ps1"
            script.write_text(r'''
$ErrorActionPreference = "Stop"
function global:aws {
    $a = @($args)
    $global:LASTEXITCODE = 0
    Add-Content $env:OCC_CLI_LOG (ConvertTo-Json -Compress -InputObject @{tool="aws";args=$a})
    $pair = ($a | Select-Object -First 2) -join " "
    switch ($pair) {
      "sts get-caller-identity" { '{"Account":"123456789012"}' }
      "iam list-open-id-connect-providers" { '{"OpenIDConnectProviderList":[{"Arn":"arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com"}]}' }
      "iam get-open-id-connect-provider" { '{"Url":"token.actions.githubusercontent.com"}' }
      "ec2 describe-managed-prefix-lists" { '{"PrefixLists":[{"PrefixListId":"pl-123456789","MaxEntries":1}]}' }
      "ec2 get-managed-prefix-list-entries" { '{"Entries":[{"Cidr":"10.0.0.0/8"}]}' }
      "service-quotas list-service-quotas" { '{"Quotas":[{"QuotaName":"Inbound or outbound rules per security group","Value":60},{"QuotaName":"Security groups per network interface","Value":5}]}' }
      "cloudformation list-stacks" { 'None' }
      "cloudformation describe-change-set" { 'CREATE_COMPLETE' }
      "cloudformation describe-stacks" {
        $outputs = @()
        foreach ($key in @("InstanceId","CloudFrontUrl","ReleaseBucketName","DeploymentSecretName","OriginSecretName","PublishRoleArn","VerifyRoleArn","DeployRoleArn","HostRoleName","IngestionRepositoryUri","DisruptionRepositoryUri","PlanningRepositoryUri","DbSetupRepositoryUri","WebRepositoryUri")) {
          $outputs += @{OutputKey=$key;OutputValue="fixture-$key"}
        }
        ConvertTo-Json -Depth 6 -Compress @{Stacks=@(@{Outputs=$outputs})}
      }
      "secretsmanager describe-secret" { '{"VersionIdsToStages":{"fixture-version":["AWSCURRENT"]}}' }
      default { }
    }
}
function global:gh {
    $a = @($args)
    $global:LASTEXITCODE = 0
    if ($a -contains "-d") { throw "unsupported gh api flag -d" }
    $payload = $null
    if ($a -contains "--input") {
      $path = $a[[Array]::IndexOf($a, "--input") + 1]
      if ($path -eq "-") { throw "unbound stdin payload" }
      $payload = Get-Content -Raw $path | ConvertFrom-Json
    }
    Add-Content $env:OCC_CLI_LOG (ConvertTo-Json -Depth 8 -Compress -InputObject @{tool="gh";args=$a;payload=$payload})
    if (($a -join " ") -match "deployment-branch-policies" -and -not ($a -contains "POST")) { '{"branch_policies":[]}' }
}
function global:python {
    $a = @($args)
    if ($a[0] -like "*egress-prefix-list.py") {
        & (Get-Command python -CommandType Application | Select-Object -First 1).Source @a
        return
    }
    if (-not ($a[0] -like "*state.py") -or -not ($a -contains "--bucket") -or -not ($a -contains "--region")) {
        throw "unexpected Python boundary"
    }
    Add-Content $env:OCC_CLI_LOG (ConvertTo-Json -Compress -InputObject @{tool="python";args=$a})
    $global:LASTEXITCODE = 0
}
& $env:OCC_BOOTSTRAP -EnvFile $env:OCC_FIXTURE_ENV -StackName fixture-stack -ApplyChangeSet
''', encoding="utf-8")
            env = dict(os.environ, OCC_CLI_LOG=str(log),
                       OCC_BOOTSTRAP=str(ROOT / "scripts/cd/bootstrap.ps1"),
                       OCC_FIXTURE_ENV=str(env_file))
            result = subprocess.run([shutil.which("pwsh") or "pwsh", "-NoProfile",
                                     "-NonInteractive", "-File", str(script)],
                                    env=env, capture_output=True, text=True,
                                    encoding="utf-8", errors="replace", timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            calls = [json.loads(line) for line in log.read_text(encoding="utf-8-sig").splitlines()]
            waiters = [c["args"] for c in calls if c["tool"] == "aws" and c["args"][:2] == ["cloudformation", "wait"]]
            self.assertTrue(waiters)
            self.assertTrue(all("--stack-name" in a and "fixture-stack" in a for a in waiters))
            environment = next(c for c in calls if c.get("payload") and
                               "deployment_branch_policy" in c["payload"])
            self.assertEqual(environment["payload"]["deployment_branch_policy"],
                             {"protected_branches": False, "custom_branch_policies": True})
            branch = next(c for c in calls if c.get("payload", {}) and c["payload"].get("name") == "main")
            self.assertEqual(branch["payload"]["type"], "branch")


if __name__ == "__main__":
    unittest.main()
