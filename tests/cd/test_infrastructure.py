"""Infrastructure provisioning and CloudFront origin contract fixtures.

Validates the CloudFormation template, its guard rules, the CloudFront Nginx
origin configuration (rendered and syntax-checked against the pinned image),
the CloudFront Compose overlay and the bootstrap scripts — all locally, with
no AWS calls.
"""
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import yaml
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TEMPLATE = ROOT / "infra" / "aws" / "ec2-cd.yaml"
GUARD = ROOT / "infra" / "aws" / "ec2-cd.guard"
CLOUDFRONT_TEMPLATE = ROOT / "infra" / "nginx" / "cloudfront.conf.template"
CLOUDFRONT_OVERLAY = ROOT / "infra" / "compose.cloudfront.yaml"
BOOTSTRAP_PS1 = ROOT / "scripts" / "cd" / "bootstrap.ps1"
BOOTSTRAP_HOST = ROOT / "scripts" / "cd" / "bootstrap-host.sh"

NGINX_IMAGE = ("nginx:1.30.6-alpine-slim@"
               "sha256:f65ed258206d1eb6c4eb6141111715a2cf94ac0a4ec9675fc2da186f7aadb163")


def template_text() -> str:
    return TEMPLATE.read_text(encoding="utf-8")


class CloudFormationContractTests(unittest.TestCase):
    def test_origin_port_and_write_methods_match_the_application(self):
        resources = yaml.load(template_text(), Loader=yaml.BaseLoader)["Resources"]
        config = resources["OccCloudFrontDistribution"]["Properties"]["DistributionConfig"]
        self.assertEqual(config["Origins"][0]["CustomOriginConfig"].get("HTTPPort"), "8080")
        self.assertEqual(set(config["DefaultCacheBehavior"].get("AllowedMethods", [])),
                         {"GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE"})

    def test_deploy_role_can_poll_and_inspect_but_deletes_only_operations(self):
        resources = yaml.load(template_text(), Loader=yaml.BaseLoader)["Resources"]
        statements = resources["OccDeployRole"]["Properties"]["Policies"][0]["PolicyDocument"]["Statement"]
        for action in ("ssm:GetCommandInvocation", "ec2:DescribeInstances", "ec2:DescribeInstanceStatus"):
            grants = [s for s in statements if action in s.get("Action", [])]
            self.assertTrue(any(s["Resource"] == "*" for s in grants), action)
        deletes = [s for s in statements if "s3:DeleteObject" in s.get("Action", [])]
        self.assertTrue(deletes)
        for grant in deletes:
            self.assertEqual(grant["Resource"], "${OccReleaseBucket.Arn}/state/operations/*")

    def test_verification_role_has_no_production_mutation_or_secret_access(self):
        resources = yaml.load(template_text(), Loader=yaml.BaseLoader)["Resources"]
        role = resources.get("OccVerifyRole")
        self.assertIsNotNone(role)
        statements = role["Properties"]["Policies"][0]["PolicyDocument"]["Statement"]
        actions = {a for s in statements for a in s.get("Action", [])}
        self.assertIn("s3:GetObjectVersion", actions)
        self.assertIn("ecr:BatchGetImage", actions)
        self.assertFalse(any(a.startswith(("ssm:", "secretsmanager:")) or
                             a in ("s3:PutObject", "ecr:PutImage") for a in actions))

    def test_template_exists_with_required_parameters(self):
        text = template_text()
        self.assertIn("t3.small", text)
        self.assertIn("Default: pslimmm/OCC", text)
        self.assertNotRegex(text, r"(?i)domainname.*parameter|acmcertificate")
        self.assertNotRegex(text, r"(?i)keyname|keypair")
        bootstrap = BOOTSTRAP_PS1.read_text(encoding="utf-8")
        self.assertIn("us-east-1", bootstrap)
        self.assertIn("pslimmm/OCC", bootstrap)

    def test_no_nat_alb_ssh_or_direct_service_ports(self):
        text = template_text()
        self.assertNotIn("AWS::ElasticLoadBalancingV2", text)
        self.assertNotIn("AWS::EC2::NatGateway", text)
        for forbidden in ("22", "5432", "8001", "8002", "8003"):
            self.assertNotRegex(
                text, rf"FromPort:\s*{forbidden}\b",
                f"unexpected ingress on {forbidden}")

    def test_origin_http_restricted_to_cloudfront_prefix_list(self):
        text = template_text()
        self.assertIn("cloudfront.origin-facing", text)
        self.assertNotRegex(text, r"FromPort:\s*8080[\s\S]{0,200}CidrIp:\s*0\.0\.0\.0/0")

    def test_public_egress_allows_only_https_and_private_dns(self):
        template = yaml.load(template_text(), Loader=yaml.BaseLoader)
        rules = template['Resources']['OccOriginSecurityGroup']['Properties']['SecurityGroupEgress']
        public = [r for r in rules if r.get('CidrIp') == '0.0.0.0/0']
        self.assertEqual(len(public), 1)
        self.assertEqual((public[0]['IpProtocol'], public[0]['FromPort'], public[0]['ToPort']),
                         ('tcp', '443', '443'))
        self.assertEqual(len(rules), 3)
        for rule in rules:
            if rule not in public:
                self.assertEqual(rule['CidrIp'], 'VpcCidr')
                self.assertEqual((rule['FromPort'], rule['ToPort']), ('53', '53'))
                self.assertIn(rule['IpProtocol'], ('tcp', 'udp'))
        self.assertNotIn('EgressAllowedPrefixListId', template['Parameters'])

    def test_security_group_rule_descriptions_are_ec2_safe(self):
        # EC2 rejects apostrophes in security-group rule descriptions.
        self.assertNotRegex(template_text(), r"Description:[^\n]*'")

    def test_bootstrap_requires_no_custom_egress_list_or_quota_change(self):
        bootstrap = BOOTSTRAP_PS1.read_text(encoding="utf-8")
        self.assertNotIn("occ-ec2-cd-egress", bootstrap)
        self.assertNotIn("EgressAllowedPrefixListId", bootstrap)
        self.assertNotIn("get-managed-prefix-list-entries", bootstrap)
        self.assertNotIn("service-quotas", bootstrap)
        self.assertIn("com.amazonaws.global.cloudfront.origin-facing", bootstrap)

    def test_bootstrap_handles_first_install_with_a_create_change_set(self):
        bootstrap = BOOTSTRAP_PS1.read_text(encoding="utf-8")
        # create-change-set defaults to UPDATE and rejects a missing stack.
        self.assertIn("--change-set-type", bootstrap)
        self.assertIn('"CREATE"', bootstrap)
        self.assertIn("list-stacks", bootstrap)
        self.assertIn("REVIEW_IN_PROGRESS", bootstrap)
        self.assertIn("stack-create-complete", bootstrap)

    def test_bootstrap_refuses_failed_stacks(self):
        bootstrap = BOOTSTRAP_PS1.read_text(encoding="utf-8")
        # ROLLBACK_COMPLETE cannot be updated; it must be deleted first.
        self.assertIn("ROLLBACK_COMPLETE", bootstrap)
        self.assertIn("delete-stack", bootstrap)

    def test_storage_is_encrypted_versioned_and_private(self):
        text = template_text()
        self.assertRegex(text, r"Encrypted:\s*true")
        self.assertRegex(text, r"DeletionPolicy:\s*Retain")
        self.assertRegex(text, r"PublicAccessBlockConfiguration:[\s\S]{0,300}RestrictPublicBuckets:\s*true")
        self.assertRegex(text, r"VersioningConfiguration:[\s\S]{0,100}Status:\s*Enabled")
        self.assertRegex(text, r"BucketEncryption")
        self.assertRegex(text, r"ImageTagMutability:\s*IMMUTABLE")
        self.assertRegex(text, r"ScanOnPush:\s*true")

    def test_imdsv2_required(self):
        self.assertRegex(template_text(), r"HttpTokens:\s*required")

    def test_release_bucket_uses_a_rotated_customer_managed_key(self):
        text = template_text()
        self.assertIn("AWS::KMS::Key", text)
        self.assertRegex(text, r"EnableKeyRotation:\s*true")
        self.assertRegex(text, r"KMSMasterKeyID:\s*!GetAtt OccReleaseKey\.Arn")
        # The roles that read or write release objects must be delegated key
        # use; the key policy keeps account administrative control.
        self.assertIn("kms:Decrypt", text)
        self.assertIn("kms:GenerateDataKey", text)
        self.assertIn("AccountAdministration", text)

    def test_cloudfront_is_fronted_by_a_waf_with_managed_rules(self):
        text = template_text()
        self.assertIn("AWS::WAFv2::WebACL", text)
        self.assertIn("AWSManagedRulesCommonRuleSet", text)
        self.assertRegex(text, r"WebACLId:\s*!GetAtt OccEdgeWebAcl\.Arn")

    def test_oidc_subjects_are_scoped_to_repository_and_environment(self):
        text = template_text()
        self.assertIn("repo:${RepositoryName}:ref:refs/heads/main", text)
        self.assertIn("repo:${RepositoryName}:environment:production", text)
        self.assertNotRegex(text, r'"StringLike"\s*,\s*"token\.actions\.githubusercontent\.com:sub"')

    def test_cloudfront_uses_default_certificate_http_origin_and_no_caching(self):
        text = template_text()
        self.assertIn("http-only", text)
        self.assertIn("redirect-to-https", text)
        self.assertIn("OriginCustomHeaders", text)
        self.assertIn("X-OCC-Origin-Secret", text)
        self.assertIn("OriginReadTimeout", text)
        self.assertIn("CloudFrontDefaultCertificate: true", text)
        self.assertNotIn("ACMCertificateArn", text)
        self.assertNotIn("CNAMEs", text)

    def test_origin_request_policy_is_the_allviewer_managed_policy(self):
        text = template_text()
        # AllViewer (216adef6-…) forwards Authorization/cookies/query strings;
        # an unrecognized policy id fails only at distribution creation.
        self.assertIn("216adef6-5c7f-47e4-b989-5492eafa07d3", text)

    def test_guard_rules_cover_critical_controls(self):
        guard = GUARD.read_text(encoding="utf-8")
        for required in ("Encrypted", "HttpTokens", "IMMUTABLE", "cloudfront.origin-facing",
                         "DeleteOnTermination"):
            self.assertIn(required, guard)

    def test_guard_rules_use_the_parseable_properties_form(self):
        # Guard 3 cannot resolve dotted selectors after a resource type and
        # skips filtered !exists clauses; the block form is what evaluates.
        guard = GUARD.read_text(encoding="utf-8")
        self.assertNotRegex(guard, r"AWS::\w+::\w+\.Properties")
        self.assertNotRegex(guard, r"\] !exists")
        self.assertIn("Properties {", guard)

    def test_publish_role_can_read_and_advance_release_state(self):
        text = template_text()
        publish = text[text.find("PolicyName: occ-publish"):text.find("OccDeployRole:")]
        # The release job reads latest-published.json and the publish job
        # writes it; both run under this role.
        self.assertIn("s3:GetObject", publish)
        self.assertIn("${OccReleaseBucket.Arn}/releases/*", publish)
        self.assertIn("${OccReleaseBucket.Arn}/state/latest-published.json", publish)

    def test_host_role_can_write_and_prune_backups(self):
        text = template_text()
        host = text[text.find("PolicyName: occ-release-artifacts"):text.find("OccInstanceProfile:")]
        self.assertIn("${OccReleaseBucket.Arn}/backups/*", host)
        self.assertIn("s3:DeleteObject", host)

    def test_deploy_role_can_list_commands_for_recovery(self):
        text = template_text()
        deploy = text[text.find("PolicyName: occ-deploy"):]
        # ListCommands does not support resource scoping, so it is granted
        # separately; recover's lookup depends on it.
        self.assertIn("ssm:ListCommands", deploy)
        self.assertIn('Resource: "*"', deploy)

    def test_bootstrap_maps_the_db_setup_repository_output(self):
        bootstrap = BOOTSTRAP_PS1.read_text(encoding="utf-8")
        # CloudFormation emits DbSetupRepositoryUri, not Db-setupRepositoryUri.
        self.assertIn("-split '-'", bootstrap)
        self.assertNotIn("$component[0].ToString()", bootstrap)

    def test_bootstrap_ignores_release_managed_image_settings(self):
        bootstrap = BOOTSTRAP_PS1.read_text(encoding="utf-8")
        # Placeholder OCC_*_IMAGE values in .env must not reach the secret,
        # where they would outrank the release manifest pins.
        self.assertIn("release-managed", bootstrap)
        self.assertIn("^OCC_[A-Z]+_IMAGE$", bootstrap)

    def test_host_data_overlay_binds_the_retained_volume(self):
        overlay = (ROOT / "infra" / "compose.host-data.yaml").read_text(encoding="utf-8")
        self.assertIn("${OCC_DATA_MOUNT", overlay)
        self.assertIn("/var/lib/postgresql", overlay)
        self.assertIn("!override", overlay)

    def test_bootstrap_writes_the_durable_marker(self):
        host = BOOTSTRAP_HOST.read_text(encoding="utf-8")
        self.assertIn(".occ-data-marker", host)


class CloudFrontTemplateTests(unittest.TestCase):
    def read(self) -> str:
        return CLOUDFRONT_TEMPLATE.read_text(encoding="utf-8")

    def test_requires_the_secret_origin_header(self):
        template = self.read()
        self.assertIn("if ($http_x_occ_origin_secret != \"${OCC_ORIGIN_SECRET}\")", template)
        self.assertIn("return 403", template)

    def test_strips_the_origin_secret_before_proxying(self):
        # The strip lives in the shared routes the template includes.
        routes = (ROOT / "infra" / "nginx" / "routes.inc.template").read_text(encoding="utf-8")
        self.assertIn('proxy_set_header X-OCC-Origin-Secret "";', routes)
        self.assertIn("include /etc/nginx/conf.d/routes.inc;", self.read())

    def test_viewer_identity_comes_from_cloudfront_headers(self):
        template = self.read()
        self.assertIn("$http_cloudfront_forwarded_proto", template)
        self.assertIn("$http_x_forwarded_for", template)
        self.assertIn("set $occ_client_ip", template)
        self.assertIn("set $occ_forwarded_proto", template)

    def test_contains_no_tls_material(self):
        template = self.read()
        self.assertNotIn("ssl_certificate", template)
        self.assertNotIn("listen ${OCC_NGINX_HTTP_INTERNAL_PORT} ssl", template)

    def test_includes_the_shared_routes(self):
        self.assertIn("include /etc/nginx/conf.d/routes.inc;", self.read())

    def test_cloudfront_fixture_loads_generated_server_and_routes(self):
        with tempfile.TemporaryDirectory(prefix="occ-cf-nginx-") as directory:
            fixture = Path(directory)
            routes = (ROOT / "infra" / "nginx" / "routes.inc.template").read_text(encoding="utf-8")
            for service, port in (("INGESTION", "18001"), ("DISRUPTION", "18002"),
                                  ("PLANNING", "18003")):
                routes = routes.replace("${OCC_" + service + "_INTERNAL_URL}",
                                        f"http://127.0.0.1:{port}")
            (fixture / "routes.inc").write_text(routes, encoding="utf-8")
            template = self.read()
            template = template.replace("${OCC_NGINX_HTTP_INTERNAL_PORT}", "18080")
            template = template.replace("${OCC_ORIGIN_SECRET}", "synthetic-origin-secret")
            (fixture / "cloudfront.conf").write_text(template, encoding="utf-8")
            gate_dir = fixture / "gate"
            gate_dir.mkdir()
            (gate_dir / "traffic-gate.inc").write_text(
                'set $occ_traffic_gate "open";\n'
                'set $occ_gate_token "synthetic-gate-token";\n', encoding="utf-8")
            nginx_conf = """pid /tmp/nginx.pid;
error_log /tmp/nginx-error.log notice;
events { worker_connections 128; }
http {
    access_log off;
    client_body_temp_path /tmp/client_body;
    proxy_temp_path /tmp/proxy;
    include /etc/nginx/conf.d/*.conf;
}
"""
            (fixture / "nginx.conf").write_text(nginx_conf, encoding="utf-8")
            for path in fixture.rglob("*"):
                if path.is_file():
                    path.chmod(0o644)
                elif path.is_dir():
                    path.chmod(0o755)
            # TemporaryDirectory creates the mount root as 0700. The Nginx
            # process runs as 101:101 and must traverse /fixture to read its
            # config, even though every file is world-readable.
            fixture.chmod(0o755)
            command = ["docker", "run", "--rm", "--user", "101:101", "--read-only",
                 "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                 "--tmpfs", "/tmp:mode=1777", "--tmpfs", "/var/cache/nginx:uid=101,gid=101,mode=1777",
                 "--mount", f"type=bind,source={fixture},target=/fixture,readonly",
                 "--mount", f"type=bind,source={fixture / 'cloudfront.conf'},target=/etc/nginx/conf.d/cloudfront.conf,readonly",
                 "--mount", f"type=bind,source={fixture / 'routes.inc'},target=/etc/nginx/conf.d/routes.inc,readonly",
                 "--mount", f"type=bind,source={gate_dir},target=/etc/nginx/gate,readonly",
                 "--entrypoint", "nginx", NGINX_IMAGE,
                 "-c", "/fixture/nginx.conf", "-t"]
            for state in ("open", "closed"):
                (gate_dir / "traffic-gate.inc").write_text(
                    f'set $occ_traffic_gate "{state}";\n'
                    'set $occ_gate_token "synthetic-gate-token";\n', encoding="utf-8")
                result = subprocess.run(command, capture_output=True, text=True,
                                        encoding="utf-8", errors="replace")
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn("syntax is ok", result.stdout + result.stderr)
                self.assertNotIn("synthetic-origin-secret", result.stdout + result.stderr)
            # Prove the mounted config is actually consumed, not the stock image config.
            (fixture / "cloudfront.conf").write_text(template + "\ninvalid_occ_directive;\n",
                                                    encoding="utf-8")
            result = subprocess.run(command, capture_output=True, text=True,
                                    encoding="utf-8", errors="replace")
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("invalid_occ_directive", result.stderr)


class CloudFrontOverlayTests(unittest.TestCase):
    def read(self) -> str:
        return CLOUDFRONT_OVERLAY.read_text(encoding="utf-8")

    def test_mounts_the_cloudfront_template_and_requires_the_secret(self):
        overlay = self.read()
        self.assertIn("cloudfront.conf.template", overlay)
        self.assertIn("${OCC_ORIGIN_SECRET:?", overlay)

    def test_exposes_only_the_http_origin_port(self):
        overlay = self.read()
        self.assertNotRegex(overlay, r'"443')
        self.assertNotRegex(overlay, r":8443")
        self.assertNotIn("occ_tls", overlay)
        self.assertNotIn("OCC_NGINX_TLS", overlay)

    def test_envsubst_filter_includes_the_origin_secret(self):
        overlay = self.read()
        self.assertIn("OCC_ORIGIN_SECRET", overlay)
        self.assertIn("NGINX_ENVSUBST_FILTER", overlay)


class BootstrapScriptTests(unittest.TestCase):
    def test_bootstrap_requires_authentication_before_mutation(self):
        script = BOOTSTRAP_PS1.read_text(encoding="utf-8")
        auth_position = min(
            position for position in (
                script.find("gh auth status"),
                script.find("get-caller-identity"))
            if position >= 0)
        mutation_position = min(
            position for position in (
                script.find("create-change-set"),
                script.find("put-secret-value"))
            if position >= 0)
        self.assertGreaterEqual(mutation_position, auth_position)
        self.assertGreater(auth_position, 0)

    def test_bootstrap_never_echoes_secret_values(self):
        script = BOOTSTRAP_PS1.read_text(encoding="utf-8")
        self.assertNotRegex(script, r"--secret-string\s+\"?\$")
        self.assertIn("cli-input-json", script)
        self.assertNotRegex(script, r"(?i)write-host.*\$envLine|write-host.*value")

    def test_bootstrap_imports_obsolete_overrides_as_names_only(self):
        script = BOOTSTRAP_PS1.read_text(encoding="utf-8")
        for obsolete in ("JAVA_TOOL_OPTIONS", "MEMORY_LIMIT", "gateway", "GATEWAY"):
            self.assertIn(obsolete, script)

    def test_host_bootstrap_formats_only_blank_volumes(self):
        script = BOOTSTRAP_HOST.read_text(encoding="utf-8")
        self.assertIn("blkid", script)
        mkfs_position = script.find("mkfs")
        self.assertGreater(mkfs_position, 0)
        guard = script[:mkfs_position]
        self.assertIn("filesystem", guard)

    def test_host_bootstrap_blocks_startup_without_the_data_mount(self):
        script = BOOTSTRAP_HOST.read_text(encoding="utf-8")
        self.assertIn("Requires=", script)
        self.assertRegex(script, r"(?i)exit 1")


class SharedRoutesTrustedIdentityTests(unittest.TestCase):
    def test_routes_use_the_overridable_client_identity(self):
        routes = (ROOT / "infra" / "nginx" / "routes.inc.template").read_text(encoding="utf-8")
        self.assertIn("set $occ_client_ip $remote_addr;", routes)
        self.assertIn("proxy_set_header X-Real-IP $occ_client_ip;", routes)
        self.assertIn("proxy_set_header X-Forwarded-For $occ_client_ip;", routes)
        self.assertIn("set $occ_forwarded_proto $scheme;", routes)
        self.assertIn("proxy_set_header X-Forwarded-Proto $occ_forwarded_proto;", routes)

    def test_rate_limit_zone_keys_on_the_trusted_identity(self):
        for template in ("default.conf.template", "cloudfront.conf.template"):
            text = (ROOT / "infra" / "nginx" / template).read_text(encoding="utf-8")
            self.assertIn("limit_req_zone $occ_client_ip zone=occ_api", text)

    def test_routes_strip_the_origin_secret_header(self):
        routes = (ROOT / "infra" / "nginx" / "routes.inc.template").read_text(encoding="utf-8")
        self.assertIn('proxy_set_header X-OCC-Origin-Secret "";', routes)


if __name__ == "__main__":
    unittest.main()
