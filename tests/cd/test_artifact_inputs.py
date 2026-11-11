"""Artifact packaging, staging and image contract fixtures.

Covers the release-input side of the EC2 CD packaging work: run/SHA-bound
artifact bundles with checksums, narrow staged Docker build contexts, packaged
JAR content requirements, runtime/web Dockerfile contracts and the immutable
Compose images overlay.
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import tarfile
import unittest
import zipfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STAGE = ROOT / "scripts" / "cd" / "stage.py"
INSPECT = ROOT / "scripts" / "cd" / "inspect_jar.py"

REVISION = "ab" * 20
NginxPin = "nginx:1.30.6-alpine-slim@sha256:f65ed258206d1eb6c4eb6141111715a2cf94ac0a4ec9675fc2da186f7aadb163"
RuntimePin = ("eclipse-temurin:25.0.4.1_1-jre-noble@"
              "sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab")

BUNDLE_FILES = ("ingestion.jar", "disruption.jar", "planning.jar", "web-dist.tar.gz")


def sha256_of(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_checksum(path: Path) -> None:
    path.with_suffix(path.suffix + ".sha256").write_text(
        f"{sha256_of(path)}  {path.name}\n", newline="\n")


class CliTestCase(unittest.TestCase):
    def run_tool(self, script: Path, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run([sys.executable, str(script), *args],
                              capture_output=True, text=True, encoding="utf-8",
                              errors="replace", cwd=ROOT)


class StageBundleTests(CliTestCase):
    def make_bundle(self, produced_at: str | None = None) -> Path:
        directory = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, directory, ignore_errors=True)
        for name in BUNDLE_FILES:
            path = directory / name
            path.write_bytes(f"bytes-of-{name}".encode())
            write_checksum(path)
        metadata = {"revision": REVISION, "runId": "1234567",
                    "producedAt": produced_at or "2026-11-10T08:00:00Z"}
        (directory / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
        return directory

    def verify(self, bundle: Path, *extra: str) -> subprocess.CompletedProcess:
        return self.run_tool(STAGE, "verify-bundle", "--bundle", str(bundle), *extra)

    def test_complete_bundle_passes(self):
        result = self.verify(self.make_bundle())
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_missing_artifact_is_rejected(self):
        bundle = self.make_bundle()
        (bundle / "planning.jar").unlink()
        result = self.verify(bundle)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("planning.jar", result.stderr)

    def test_wrong_checksum_is_rejected(self):
        bundle = self.make_bundle()
        (bundle / "disruption.jar").write_bytes(b"tampered")
        result = self.verify(bundle)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("disruption.jar", result.stderr)

    def test_wrong_revision_is_rejected(self):
        bundle = self.make_bundle()
        result = self.verify(bundle, "--revision", "cd" * 20)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("revision", result.stderr.lower())

    def test_matching_revision_passes(self):
        bundle = self.make_bundle()
        result = self.verify(bundle, "--revision", REVISION)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_expired_artifact_is_rejected(self):
        stale = (datetime.now(timezone.utc) - timedelta(hours=6)).strftime("%Y-%m-%dT%H:%M:%SZ")
        bundle = self.make_bundle(produced_at=stale)
        result = self.verify(bundle, "--max-age-hours", "2")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("expired", result.stderr.lower())

    def test_fresh_artifact_passes_age_check(self):
        fresh = (datetime.now(timezone.utc) - timedelta(minutes=10)).strftime("%Y-%m-%dT%H:%M:%SZ")
        bundle = self.make_bundle(produced_at=fresh)
        result = self.verify(bundle, "--max-age-hours", "2")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_missing_metadata_is_rejected(self):
        bundle = self.make_bundle()
        (bundle / "metadata.json").unlink()
        result = self.verify(bundle)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("metadata", result.stderr.lower())

    def test_malformed_revision_in_metadata_is_rejected(self):
        bundle = self.make_bundle()
        metadata = json.loads((bundle / "metadata.json").read_text(encoding="utf-8"))
        metadata["revision"] = "not-a-sha"
        (bundle / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
        result = self.verify(bundle)
        self.assertNotEqual(result.returncode, 0)


class StageContextTests(CliTestCase):
    def make_bundle(self) -> Path:
        directory = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, directory, ignore_errors=True)
        for name in BUNDLE_FILES:
            path = directory / name
            if name == "web-dist.tar.gz":
                with tempfile.TemporaryDirectory() as tmp:
                    dist = Path(tmp) / "dist"
                    dist.mkdir()
                    (dist / "index.html").write_text("<html></html>", encoding="utf-8")
                    (dist / "assets").mkdir()
                    (dist / "assets" / "app.js").write_text("console.log(1)", encoding="utf-8")
                    # The CI packaging step archives the dist contents at the
                    # archive root: tar -czf web-dist.tar.gz -C frontend/dist .
                    with tarfile.open(path, "w:gz") as tar:
                        for item in sorted(dist.rglob("*")):
                            if item.is_file():
                                tar.add(item, arcname=f"./{item.relative_to(dist).as_posix()}")
            else:
                path.write_bytes(f"bytes-of-{name}".encode())
            write_checksum(path)
        (directory / "metadata.json").write_text(json.dumps(
            {"revision": REVISION, "runId": "1234567",
             "producedAt": "2026-11-10T08:00:00Z"}), encoding="utf-8")
        return directory

    def test_runtime_context_contains_only_the_service_jar(self):
        bundle = self.make_bundle()
        output = Path(tempfile.mkdtemp()) / "context"
        self.addCleanup(shutil.rmtree, output.parent, ignore_errors=True)
        result = self.run_tool(STAGE, "runtime-context", "--bundle", str(bundle),
                               "--service", "ingestion", "--output", str(output))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(sorted(p.name for p in output.rglob("*")), ["app.jar"])

    def test_web_context_contains_dist_and_templates_only(self):
        bundle = self.make_bundle()
        output = Path(tempfile.mkdtemp()) / "context"
        self.addCleanup(shutil.rmtree, output.parent, ignore_errors=True)
        result = self.run_tool(STAGE, "web-context", "--bundle", str(bundle),
                               "--output", str(output))
        self.assertEqual(result.returncode, 0, result.stderr)
        names = sorted(str(p.relative_to(output)) for p in output.rglob("*"))
        self.assertIn(str(Path("dist") / "index.html"), names)
        # The web image bakes the shared routing include only; server blocks
        # are mounted per deployment mode.
        self.assertIn(str(Path("templates") / "routes.inc.template"), names)
        self.assertNotIn(str(Path("templates") / "default.conf.template"), names)
        for name in names:
            self.assertTrue(name.startswith(("dist", "templates")), name)

    def test_context_fails_without_valid_bundle(self):
        bundle = self.make_bundle()
        (bundle / "ingestion.jar.sha256").unlink()
        output = Path(tempfile.mkdtemp()) / "context"
        self.addCleanup(shutil.rmtree, output.parent, ignore_errors=True)
        result = self.run_tool(STAGE, "runtime-context", "--bundle", str(bundle),
                               "--service", "ingestion", "--output", str(output))
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(list(output.rglob("*")) if output.exists() else [], [])


class InspectJarTests(CliTestCase):
    def make_jar(self, entries: dict) -> Path:
        handle, name = tempfile.mkstemp(suffix=".jar")
        os.close(handle)
        path = Path(name)
        self.addCleanup(lambda: path.unlink(missing_ok=True))
        with zipfile.ZipFile(path, "w") as jar:
            for entry, payload in entries.items():
                jar.writestr(entry, payload)
        return path

    def complete_entries(self) -> dict:
        return {
            "META-INF/MANIFEST.MF": "Manifest-Version: 1.0\n",
            "BOOT-INF/classes/static/v3/ingestion.json": '{"openapi":"3.1.0"}',
            "BOOT-INF/classes/static/swagger-ui/index.html": "<html>swagger</html>",
            "BOOT-INF/lib/jackson-datatype-jsr310-2.21.7.jar": "PK",
            "BOOT-INF/lib/swagger-ui-5.32.14.jar": "PK",
            "BOOT-INF/classes/application.yml": "occ: {}\n",
        }

    def inspect(self, jar: Path, service: str = "ingestion") -> subprocess.CompletedProcess:
        return self.run_tool(INSPECT, str(jar), "--service", service)

    def test_complete_jar_passes(self):
        result = self.inspect(self.make_jar(self.complete_entries()))
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_missing_committed_contract_fails(self):
        entries = self.complete_entries()
        del entries["BOOT-INF/classes/static/v3/ingestion.json"]
        result = self.inspect(self.make_jar(entries))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("v3/ingestion.json", result.stderr)

    def test_contract_must_be_valid_json(self):
        entries = self.complete_entries()
        entries["BOOT-INF/classes/static/v3/ingestion.json"] = "not json"
        result = self.inspect(self.make_jar(entries))
        self.assertNotEqual(result.returncode, 0)

    def test_missing_shared_swagger_index_fails(self):
        entries = self.complete_entries()
        del entries["BOOT-INF/classes/static/swagger-ui/index.html"]
        result = self.inspect(self.make_jar(entries))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("swagger-ui/index.html", result.stderr)

    def test_missing_jackson_java_time_support_fails(self):
        entries = self.complete_entries()
        del entries["BOOT-INF/lib/jackson-datatype-jsr310-2.21.7.jar"]
        result = self.inspect(self.make_jar(entries))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("jsr310", result.stderr.lower())

    def test_missing_swagger_webjar_fails(self):
        entries = self.complete_entries()
        del entries["BOOT-INF/lib/swagger-ui-5.32.14.jar"]
        result = self.inspect(self.make_jar(entries))
        self.assertNotEqual(result.returncode, 0)

    def test_springdoc_must_stay_test_only(self):
        entries = self.complete_entries()
        entries["BOOT-INF/lib/springdoc-openapi-starter-webmvc-ui-3.1.1.jar"] = "PK"
        result = self.inspect(self.make_jar(entries))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("springdoc", result.stderr.lower())


class DockerfileContractTests(unittest.TestCase):
    def read(self, name: str) -> str:
        return (ROOT / "infra" / name).read_text(encoding="utf-8")

    def test_java_runtime_pins_the_same_jre_base(self):
        source = self.read("java.Dockerfile")
        runtime = self.read("java-runtime.Dockerfile")
        pinned = re.search(r"RUNTIME_IMAGE=(\S+)", source)
        self.assertIsNotNone(pinned)
        self.assertIn(pinned.group(1), runtime)
        self.assertNotRegex(runtime, r"mvnw|services/")

    def test_java_runtime_keeps_hardening_and_uid(self):
        runtime = self.read("java-runtime.Dockerfile")
        self.assertIn("USER 10001:10001", runtime)
        self.assertIn("https://archive.ubuntu.com", runtime)
        self.assertIn("apt-get upgrade -y", runtime)
        self.assertIn("ENTRYPOINT", runtime)
        self.assertIn("COPY app.jar", runtime)

    def test_web_image_pins_the_compose_nginx_digest(self):
        compose = (ROOT / "compose.yaml").read_text(encoding="utf-8")
        nginx_pin = re.search(r"image:\s*(" + re.escape(NginxPin) + r")", compose)
        self.assertIsNotNone(nginx_pin, "compose.yaml nginx pin changed")
        web = self.read("web.Dockerfile")
        self.assertIn(NginxPin, web)
        self.assertIn("COPY dist/", web)
        self.assertIn("COPY templates/", web)
        self.assertNotIn("occ_tls", web)
        self.assertNotRegex(web, r"frontend/node_modules|\.env")

    def test_web_image_runs_as_the_unprivileged_nginx_account(self):
        # Compose already runs the edge as 101:101; the image itself must not
        # fall back to root when the scanner or another runtime starts it.
        self.assertIn("USER 101:101", self.read("web.Dockerfile"))

    def test_dockerignore_keeps_real_inputs_out_of_broad_contexts(self):
        ignore = (ROOT / ".dockerignore").read_text(encoding="utf-8")
        for required in (".env", "**/target", "**/node_modules", "frontend/dist"):
            self.assertIn(required, ignore)


class ComposeImagesOverlayTests(unittest.TestCase):
    def read(self) -> str:
        return (ROOT / "infra" / "compose.images.yaml").read_text(encoding="utf-8")

    def test_pins_all_five_images_with_required_digests(self):
        overlay = self.read()
        for variable in ("OCC_INGESTION_IMAGE", "OCC_DISRUPTION_IMAGE", "OCC_PLANNING_IMAGE",
                         "OCC_DB_SETUP_IMAGE", "OCC_WEB_IMAGE"):
            pattern = rf"image:\s*\${{{variable}:.+}}"
            self.assertRegex(overlay, pattern), variable
        self.assertNotIn("occ-ingestion:local", overlay)

    def test_removes_production_source_and_dist_mounts(self):
        overlay = self.read()
        self.assertIn("!reset []", overlay)
        self.assertNotIn("frontend/dist", overlay)
        self.assertNotIn("routes.inc.template:", overlay.split("volumes:", 1)[-1])

    def test_does_not_force_tls_configuration(self):
        overlay = self.read()
        self.assertNotIn("occ_tls", overlay)
        self.assertNotIn("OCC_NGINX_TLS", overlay)
        self.assertNotIn("443", overlay)


if __name__ == "__main__":
    unittest.main()
