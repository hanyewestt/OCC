"""Release input, manifest and pointer contract fixtures.

Builds small disposable git repositories whose commits exercise the build
ownership table, then drives scripts/cd/release.py through its CLI so the
tested interface is the one the pipeline calls.
"""
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RELEASE = ROOT / "scripts" / "cd" / "release.py"

_spec = importlib.util.spec_from_file_location("occ_release", RELEASE)
release = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(release)

ECR = "123456789012.dkr.ecr.us-east-1.amazonaws.com/occ"
FIVE = ("ingestion", "disruption", "planning", "db-setup", "web")
JAVA = ("ingestion", "disruption", "planning")


def digest(component: str) -> str:
    return f"{ECR}/{component}@sha256:{'ab' * 32}"


def all_digests() -> dict:
    return {component: digest(component) for component in FIVE}


INITIAL_FILES = {
    "pom.xml": "<project/>",
    "mvnw": "wrapper",
    "mvnw.cmd": "wrapper",
    ".mvn/wrapper/maven-wrapper.properties": "distributionUrl=x",
    ".dockerignore": "**/target\n",
    ".gitignore": "target/\n",
    ".env.example": "POSTGRES_DB=occ\n",
    "README.md": "occ",
    "AGENTS.md": "agent instructions",
    "LICENSE": "MIT",
    ".github/workflows/ci.yml": "name: Core CI\n",
    "compose.yaml": "services: {}\n",
    "compose.override.yaml": "services: {}\n",
    "infra/compose.production.yaml": "services: {}\n",
    "infra/compose.test.yaml": "services: {}\n",
    "infra/java.Dockerfile": "FROM eclipse-temurin\n",
    "infra/flyway.Dockerfile": "FROM flyway\n",
    "infra/db-setup.sh": "#!/bin/sh\n",
    "infra/postgres/bootstrap.sql": "SELECT 1;\n",
    "infra/swagger-ui/index.html": "<html></html>\n",
    "infra/nginx/default.conf.template": "server {}\n",
    "infra/pgadmin/servers.json": "{}\n",
    "infra/aws/.gitkeep": "",
    "scripts/app.sh": "#!/bin/sh\n",
    "scripts/app.ps1": "# launcher\n",
    "docs/README.md": "docs",
    "tests/system/test_example.py": "import unittest\n",
    "frontend/src/main.tsx": "export {};\n",
    "frontend/index.html": "<html></html>\n",
    "frontend/package.json": "{}\n",
    "frontend/package-lock.json": "{}\n",
    "frontend/vite.config.ts": "export default {};\n",
    "frontend/tsconfig.json": "{}\n",
    "frontend/components.json": "{}\n",
    "frontend/eslint.config.js": "export {};\n",
    "frontend/test/app.spec.ts": "it();\n",
}


def service_files(service: str) -> dict:
    return {
        f"services/{service}/pom.xml": "<project/>",
        f"services/{service}/src/main/java/occ/App.java": "class App {}",
        f"services/{service}/src/main/resources/application.yml": "occ: {}\n",
        f"services/{service}/src/main/resources/db/migration/V001__init.sql": "CREATE TABLE t();\n",
        f"services/{service}/openapi/{service}.json": "{}",
        f"services/{service}/src/test/java/occ/AppTest.java": "class AppTest {}",
    }


class ReleaseTestCase(unittest.TestCase):
    def make_repo(self) -> Path:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        repo = Path(tmp.name)
        files = dict(INITIAL_FILES)
        for service in JAVA:
            files.update(service_files(service))
        self.write(repo, files)
        self.git(repo, "init", "-b", "main")
        self.git(repo, "config", "core.autocrlf", "false")
        self.commit(repo, "initial fixture")
        return repo

    def write(self, repo: Path, files: dict) -> None:
        for name, content in files.items():
            path = repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, newline="\n")

    def git(self, repo: Path, *args: str) -> str:
        result = subprocess.run(["git", *args], cwd=repo, capture_output=True, text=True,
                                encoding="utf-8", errors="replace")
        if result.returncode != 0:
            raise AssertionError(f"git {' '.join(args)} failed: {result.stderr}")
        return result.stdout.strip()

    def commit(self, repo: Path, message: str) -> str:
        self.git(repo, "add", "-A")
        self.git(repo, "-c", "user.name=fixture", "-c", "user.email=fixture@example.com",
                 "commit", "-m", message)
        return self.git(repo, "rev-parse", "HEAD")

    def run_cli(self, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run([sys.executable, str(RELEASE), *args],
                              capture_output=True, text=True, encoding="utf-8",
                              errors="replace", cwd=ROOT)

    def plan_json(self, repo: Path, head: str, base: str | None = None,
                  published: dict | None = None, refresh: bool = False) -> dict:
        args = ["plan", "--head", head, "--repo", str(repo)]
        if base is not None:
            args += ["--base", base]
        if refresh:
            args += ["--refresh"]
        if published is not None:
            args += ["--published", self.staged(published)]
        result = self.run_cli(*args)
        self.assertEqual(result.returncode, 0, result.stderr or result.stdout)
        return json.loads(result.stdout)

    def manifest_json(self, plan: dict, previous: dict | None, digests: dict,
                      release_id: str, compatibility: str, evidence: dict | None = None,
                      extra_args: list[str] | None = None) -> subprocess.CompletedProcess:
        args = ["manifest", "--plan", self.staged(plan)]
        if previous is not None:
            args += ["--previous", self.staged(previous)]
        args += ["--digests", self.staged(digests),
                 "--release-id", release_id, "--compatibility", compatibility]
        if evidence is not None:
            args += ["--evidence", self.staged(evidence)]
        if extra_args:
            args += extra_args
        return self.run_cli(*args)

    def published_manifest(self, plan: dict, release_id: str) -> dict:
        result = self.manifest_json(plan, None, all_digests(), release_id, "unknown")
        self.assertEqual(result.returncode, 0, result.stderr)
        return json.loads(result.stdout)

    def staged(self, payload: dict) -> str:
        handle, path = tempfile.mkstemp(suffix=".json")
        os.close(handle)
        path = Path(path)
        self.addCleanup(lambda: path.unlink(missing_ok=True))
        path.write_text(json.dumps(payload), encoding="utf-8")
        return str(path)

    def make_plan(self) -> dict:
        repo = self.make_repo()
        head = self.git(repo, "rev-parse", "HEAD")
        return self.plan_json(repo, head, head)


class PlanOwnershipTests(ReleaseTestCase):
    def test_exact_five_input_hashes_always_present(self):
        repo = self.make_repo()
        head = self.git(repo, "rev-parse", "HEAD")
        plan = self.plan_json(repo, head, head)
        self.assertEqual(sorted(plan["inputHashes"]), sorted(FIVE))

    def test_service_only_change_builds_owner_image(self):
        repo = self.make_repo()
        base = self.git(repo, "rev-parse", "HEAD")
        self.write(repo, {"services/disruption/src/main/java/occ/App.java": "class App2 {}"})
        head = self.commit(repo, "disruption change")
        plan = self.plan_json(repo, head, base)
        self.assertEqual(plan["buildComponents"], ["disruption"])
        self.assertTrue(plan["deployRequired"])

    def test_frontend_only_change_builds_web(self):
        repo = self.make_repo()
        base = self.git(repo, "rev-parse", "HEAD")
        self.write(repo, {"frontend/src/main.tsx": "export {};\n// changed"})
        head = self.commit(repo, "frontend change")
        plan = self.plan_json(repo, head, base)
        self.assertEqual(plan["buildComponents"], ["web"])
        self.assertTrue(plan["deployRequired"])

    def test_committed_openapi_affects_owner(self):
        repo = self.make_repo()
        base = self.git(repo, "rev-parse", "HEAD")
        self.write(repo, {"services/planning/openapi/planning.json": "{\"x\": 1}"})
        head = self.commit(repo, "planning contract")
        plan = self.plan_json(repo, head, base)
        self.assertEqual(plan["buildComponents"], ["planning"])

    def test_migration_affects_owner_and_combined_db_setup(self):
        repo = self.make_repo()
        base = self.git(repo, "rev-parse", "HEAD")
        self.write(repo, {"services/ingestion/src/main/resources/db/migration/V002__next.sql":
                          "ALTER TABLE t ADD COLUMN c int;\n"})
        head = self.commit(repo, "ingestion migration")
        plan = self.plan_json(repo, head, base)
        self.assertEqual(sorted(plan["buildComponents"]), ["db-setup", "ingestion"])

    def test_setup_inputs_rebuild_only_db_setup(self):
        repo = self.make_repo()
        for path in ("infra/db-setup.sh", "infra/flyway/ingestion/afterMigrate.sql",
                     "infra/postgres/bootstrap.sql", "infra/flyway.Dockerfile"):
            base = self.git(repo, "rev-parse", "HEAD")
            self.write(repo, {path: "# changed\n"})
            head = self.commit(repo, f"change {path}")
            plan = self.plan_json(repo, head, base)
            self.assertEqual(plan["buildComponents"], ["db-setup"], path)

    def test_shared_java_inputs_rebuild_three_app_images(self):
        repo = self.make_repo()
        for path, content in (("pom.xml", "<project version=2/>"),
                              (".mvn/wrapper/maven-wrapper.properties", "distributionUrl=y\n"),
                              ("infra/swagger-ui/index.html", "<html>v2</html>\n"),
                              ("infra/java.Dockerfile", "FROM eclipse-temurin:25\n")):
            base = self.git(repo, "rev-parse", "HEAD")
            self.write(repo, {path: content})
            head = self.commit(repo, f"change {path}")
            plan = self.plan_json(repo, head, base)
            self.assertEqual(sorted(plan["buildComponents"]), sorted(JAVA), path)

    def test_nginx_and_web_build_inputs_rebuild_web(self):
        repo = self.make_repo()
        for path, content in (("infra/nginx/default.conf.template", "server { v2 }\n"),
                              ("frontend/vite.config.ts", "export default {v: 2};\n"),
                              ("frontend/package-lock.json", "{\"lock\": 2}")):
            base = self.git(repo, "rev-parse", "HEAD")
            self.write(repo, {path: content})
            head = self.commit(repo, f"change {path}")
            plan = self.plan_json(repo, head, base)
            self.assertEqual(plan["buildComponents"], ["web"], path)

    def test_config_only_release_rebuilds_nothing(self):
        repo = self.make_repo()
        for path in ("compose.yaml", "infra/compose.production.yaml",
                     "infra/compose.host-data.yaml", ".github/workflows/cd.yml"):
            base = self.git(repo, "rev-parse", "HEAD")
            self.write(repo, {path: "# changed\n"})
            head = self.commit(repo, f"change {path}")
            plan = self.plan_json(repo, head, base)
            self.assertEqual(plan["buildComponents"], [], path)
            self.assertTrue(plan["deployRequired"], path)

    def test_runtime_dockerfile_invalidates_three_java_images(self):
        repo = self.make_repo()
        base = self.git(repo, "rev-parse", "HEAD")
        before = self.plan_json(repo, base, base)["inputHashes"]
        self.write(repo, {"infra/java-runtime.Dockerfile": "FROM changed-runtime\n"})
        head = self.commit(repo, "runtime base update")
        plan = self.plan_json(repo, head, base)
        self.assertEqual(plan["buildComponents"], sorted(JAVA))
        for service in JAVA:
            self.assertNotEqual(before[service], plan["inputHashes"][service])

    def test_setup_input_inventory_tracks_actual_migration_files(self):
        repo = self.make_repo()
        head = self.git(repo, "rev-parse", "HEAD")
        plan = self.plan_json(repo, head, head)
        self.assertIn("services/ingestion/src/main/resources/db/migration/V001__init.sql", plan.get("setupInputFiles", {}))
        manifest = self.manifest_json(plan, None, all_digests(), "rel-1", "unknown")
        self.assertEqual(json.loads(manifest.stdout)["setupInputFiles"], plan["setupInputFiles"])

    def test_build_definition_change_invalidates_image_owners(self):
        repo = self.make_repo()
        base = self.git(repo, "rev-parse", "HEAD")
        self.write(repo, {".github/workflows/ci.yml": "build-arg: changed\n"})
        head = self.commit(repo, "image build arguments")
        self.assertEqual(self.plan_json(repo, head, base)["buildComponents"], sorted(FIVE))

    def test_docs_test_and_launcher_changes_need_no_release(self):
        repo = self.make_repo()
        base = self.git(repo, "rev-parse", "HEAD")
        self.write(repo, {
            "docs/README.md": "docs v2",
            "AGENTS.md": "agent instructions v2",
            "tests/system/test_example.py": "import unittest  # v2\n",
            "scripts/app.sh": "#!/bin/sh\n# v2\n",
            "scripts/app.ps1": "# launcher v2\n",
            "compose.override.yaml": "services: {v2: {}}\n",
            "frontend/test/app.spec.ts": "it('v2');\n",
            "frontend/eslint.config.js": "export { v2 };\n",
            "services/ingestion/src/test/java/occ/AppTest.java": "class AppTest2 {}",
            "infra/pgadmin/servers.json": "{\"v2\": true}",
            "infra/compose.test.yaml": "services: {v2: {}}\n",
            "README.md": "occ v2",
        })
        head = self.commit(repo, "docs, tests and launchers only")
        plan = self.plan_json(repo, head, base)
        self.assertEqual(plan["buildComponents"], [])
        self.assertFalse(plan["deployRequired"])

    def test_unknown_path_rebuilds_all_five(self):
        repo = self.make_repo()
        base = self.git(repo, "rev-parse", "HEAD")
        self.write(repo, {"vendor/unrecognized.so": "binary-ish"})
        head = self.commit(repo, "unrecognized input")
        plan = self.plan_json(repo, head, base)
        self.assertEqual(sorted(plan["buildComponents"]), sorted(FIVE))
        self.assertTrue(plan["deployRequired"])

    def test_dockerignore_rebuilds_all_five(self):
        repo = self.make_repo()
        base = self.git(repo, "rev-parse", "HEAD")
        self.write(repo, {".dockerignore": "**/target\n**/dist\n"})
        head = self.commit(repo, "context filter change")
        plan = self.plan_json(repo, head, base)
        self.assertEqual(sorted(plan["buildComponents"]), sorted(FIVE))

    def test_deleted_paths_still_classified(self):
        repo = self.make_repo()
        base = self.git(repo, "rev-parse", "HEAD")
        (repo / "services/planning/pom.xml").unlink()
        head = self.commit(repo, "delete planning pom")
        plan = self.plan_json(repo, head, base)
        self.assertEqual(plan["buildComponents"], ["planning"])

    def test_service_rename_counts_old_and_new_owners(self):
        repo = self.make_repo()
        base = self.git(repo, "rev-parse", "HEAD")
        self.write(repo, {"services/disruption/src/main/java/occ/Moved.java": "class Moved {}"})
        (repo / "services/disruption/src/main/java/occ/App.java").unlink()
        head = self.commit(repo, "rename disruption source")
        plan = self.plan_json(repo, head, base)
        self.assertEqual(plan["buildComponents"], ["disruption"])

    def test_identical_content_reversion_needs_no_release(self):
        repo = self.make_repo()
        base = self.git(repo, "rev-parse", "HEAD")
        original = (repo / "services/disruption/src/main/java/occ/App.java").read_text()
        self.write(repo, {"services/disruption/src/main/java/occ/App.java": "class AppX {}"})
        changed = self.commit(repo, "temporary change")
        self.write(repo, {"services/disruption/src/main/java/occ/App.java": original})
        head = self.commit(repo, "revert temporary change")
        plan = self.plan_json(repo, head, changed)
        self.assertEqual(plan["buildComponents"], ["disruption"])
        reverted = self.plan_json(repo, head, base)
        self.assertEqual(reverted["buildComponents"], [])
        self.assertFalse(reverted["deployRequired"])

    def test_missing_baseline_fails_with_clear_error(self):
        repo = self.make_repo()
        head = self.git(repo, "rev-parse", "HEAD")
        result = self.run_cli("plan", "--head", head, "--repo", str(repo))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("baseline", result.stderr.lower())

    def test_divergent_history_fails(self):
        repo = self.make_repo()
        base = self.git(repo, "rev-parse", "HEAD")
        self.git(repo, "checkout", "-b", "side")
        self.write(repo, {"docs/side.md": "side"})
        side_head = self.commit(repo, "side commit")
        self.git(repo, "checkout", "main")
        self.write(repo, {"docs/main.md": "main line"})
        head = self.commit(repo, "main commit")
        self.assertNotEqual(head, side_head)
        result = self.run_cli("plan", "--head", head, "--base", side_head, "--repo", str(repo))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("divergen", result.stderr.lower())


class ReuseAndRefreshTests(ReleaseTestCase):
    def test_refresh_rebuild_builds_all_five(self):
        repo = self.make_repo()
        head = self.git(repo, "rev-parse", "HEAD")
        published = self.published_manifest(self.plan_json(repo, head, head), "rel-1")
        result = self.run_cli("plan", "--head", head, "--repo", str(repo),
                              "--published", self.staged(published), "--rebuild")
        self.assertEqual(result.returncode, 0, result.stderr)
        plan = json.loads(result.stdout)
        self.assertEqual(plan["buildComponents"], sorted(FIVE))
        self.assertTrue(plan["refresh"])
        self.assertTrue(plan["deployRequired"])

    def test_first_release_rebuild_includes_initial_commit_inputs(self):
        repo = self.make_repo()
        head = self.git(repo, "rev-parse", "HEAD")
        result = self.run_cli("plan", "--head", head, "--base", head,
                             "--repo", str(repo), "--rebuild")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["buildComponents"], sorted(FIVE))

    def test_missing_baseline_with_published_uses_published_revision(self):
        repo = self.make_repo()
        head = self.git(repo, "rev-parse", "HEAD")
        plan = self.plan_json(repo, head, published={"revision": head, "inputHashes": {
            component: "stale" for component in FIVE}})
        self.assertEqual(plan["baseRevision"], head)
        self.assertEqual(sorted(plan["buildComponents"]), sorted(FIVE))

    def test_published_reuse_skips_unchanged_components(self):
        repo = self.make_repo()
        base = self.git(repo, "rev-parse", "HEAD")
        release_plan = self.plan_json(repo, base, base)
        published = self.published_manifest(release_plan, "rel-1")
        self.write(repo, {"frontend/src/main.tsx": "export {};\n// changed"})
        head = self.commit(repo, "frontend change")
        plan = self.plan_json(repo, head, published=published)
        self.assertEqual(plan["buildComponents"], ["web"])
        self.assertEqual(plan["baseRevision"], base)

    def test_paused_multi_commit_reuse_accumulates(self):
        repo = self.make_repo()
        base = self.git(repo, "rev-parse", "HEAD")
        published = self.published_manifest(self.plan_json(repo, base, base), "rel-1")
        self.write(repo, {"services/ingestion/src/main/java/occ/App.java": "class A2 {}"})
        self.commit(repo, "paused change one")
        self.write(repo, {"services/ingestion/src/main/java/occ/App.java": "class A3 {}"})
        second = self.commit(repo, "paused change two")
        plan = self.plan_json(repo, second, published=published)
        self.assertEqual(plan["buildComponents"], ["ingestion"])
        self.write(repo, {"frontend/src/main.tsx": "// v3"})
        third = self.commit(repo, "paused change three")
        plan = self.plan_json(repo, third, published=published)
        self.assertEqual(sorted(plan["buildComponents"]), ["ingestion", "web"])
        self.assertTrue(plan["deployRequired"])

    def test_failed_release_keeps_published_baseline(self):
        repo = self.make_repo()
        base = self.git(repo, "rev-parse", "HEAD")
        published = self.published_manifest(self.plan_json(repo, base, base), "rel-1")
        self.write(repo, {"services/planning/src/main/java/occ/App.java": "class P2 {}"})
        self.commit(repo, "failed release change")
        self.write(repo, {"services/planning/src/main/java/occ/App.java": "class P3 {}"})
        head = self.commit(repo, "retry change")
        plan = self.plan_json(repo, head, published=published)
        self.assertEqual(plan["buildComponents"], ["planning"])
        self.assertEqual(plan["baseRevision"], base)

    def test_incomplete_published_record_rebuilds_all(self):
        repo = self.make_repo()
        base = self.git(repo, "rev-parse", "HEAD")
        self.write(repo, {"docs/README.md": "docs v2"})
        head = self.commit(repo, "docs only")
        plan = self.plan_json(repo, head, published={"revision": base, "inputHashes": {
            component: "stale" for component in FIVE}})
        self.assertEqual(sorted(plan["buildComponents"]), sorted(FIVE))

    def test_no_change_without_refresh_needs_no_release(self):
        repo = self.make_repo()
        base = self.git(repo, "rev-parse", "HEAD")
        published = self.published_manifest(self.plan_json(repo, base, base), "rel-1")
        plan = self.plan_json(repo, base, published=published)
        self.assertFalse(plan["deployRequired"])
        self.assertEqual(plan["buildComponents"], [])

    def test_same_sha_refresh_releases_without_rebuild(self):
        repo = self.make_repo()
        base = self.git(repo, "rev-parse", "HEAD")
        published = self.published_manifest(self.plan_json(repo, base, base), "rel-1")
        plan = self.plan_json(repo, base, published=published, refresh=True)
        self.assertTrue(plan["refresh"])
        self.assertTrue(plan["deployRequired"])
        self.assertEqual(plan["buildComponents"], [])
        result = self.manifest_json(plan, published, {}, "rel-1-refresh-1", "unknown")
        self.assertEqual(result.returncode, 0, result.stderr)
        manifest = json.loads(result.stdout)
        self.assertEqual(manifest["revision"], published["revision"])
        self.assertEqual(manifest["releaseId"], "rel-1-refresh-1")
        self.assertEqual(sorted(manifest["images"]), sorted(FIVE))

    def test_same_revision_without_refresh_is_rejected(self):
        repo = self.make_repo()
        base = self.git(repo, "rev-parse", "HEAD")
        published = self.published_manifest(self.plan_json(repo, base, base), "rel-1")
        plan = self.plan_json(repo, base, published=published)
        result = self.manifest_json(plan, published, {}, "rel-2", "unknown")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("refresh", result.stderr.lower())

    def test_refresh_release_ids_must_differ(self):
        repo = self.make_repo()
        base = self.git(repo, "rev-parse", "HEAD")
        published = self.published_manifest(self.plan_json(repo, base, base), "rel-1")
        plan = self.plan_json(repo, base, published=published, refresh=True)
        result = self.manifest_json(plan, published, {}, "rel-1", "unknown")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("release id", result.stderr.lower())


class ManifestValidationTests(ReleaseTestCase):
    def test_manifest_has_schema_two_and_required_fields(self):
        plan = self.make_plan()
        result = self.manifest_json(plan, None, all_digests(), "rel-1", "unknown")
        self.assertEqual(result.returncode, 0, result.stderr)
        manifest = json.loads(result.stdout)
        self.assertEqual(manifest["schemaVersion"], 2)
        self.assertEqual(manifest["revision"], plan["revision"])
        self.assertIsNone(manifest["baselineRevision"])
        self.assertEqual(sorted(manifest["images"]), sorted(FIVE))
        self.assertEqual(manifest["builtComponents"], plan["buildComponents"])
        self.assertEqual(manifest["dbSetupInputHash"], plan["inputHashes"]["db-setup"])
        self.assertEqual(manifest["migrationCompatibility"], "unknown")

    def test_manifest_baseline_revision_comes_from_previous(self):
        repo = self.make_repo()
        base = self.git(repo, "rev-parse", "HEAD")
        previous = self.published_manifest(self.plan_json(repo, base, base), "rel-0")
        self.write(repo, {"docs/README.md": "docs v2"})
        head = self.commit(repo, "docs change")
        plan = self.plan_json(repo, head, base)
        result = self.manifest_json(plan, previous, all_digests(), "rel-1", "unknown")
        self.assertEqual(result.returncode, 0, result.stderr)
        manifest = json.loads(result.stdout)
        self.assertEqual(manifest["baselineRevision"], previous["revision"])

    def test_reused_component_digests_come_from_previous(self):
        repo = self.make_repo()
        base = self.git(repo, "rev-parse", "HEAD")
        previous = self.published_manifest(self.plan_json(repo, base, base), "rel-1")
        self.write(repo, {"frontend/src/main.tsx": "// v2"})
        head = self.commit(repo, "web change")
        plan = self.plan_json(repo, head, published=previous)
        result = self.manifest_json(plan, previous, {"web": digest("web")}, "rel-2", "unknown")
        self.assertEqual(result.returncode, 0, result.stderr)
        manifest = json.loads(result.stdout)
        self.assertEqual(sorted(manifest["builtComponents"]), ["web"])
        self.assertEqual(manifest["images"]["ingestion"], previous["images"]["ingestion"])
        self.assertEqual(manifest["images"]["web"], digest("web"))

    def test_missing_digest_for_built_component_fails(self):
        plan = self.make_plan()
        digests = all_digests()
        del digests["db-setup"]
        result = self.manifest_json(plan, None, digests, "rel-1", "unknown")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("db-setup", result.stderr)

    def test_missing_digest_for_reused_component_fails(self):
        repo = self.make_repo()
        base = self.git(repo, "rev-parse", "HEAD")
        previous = self.published_manifest(self.plan_json(repo, base, base), "rel-1")
        del previous["images"]["planning"]
        self.write(repo, {"frontend/src/main.tsx": "// v2"})
        head = self.commit(repo, "web change")
        plan = self.plan_json(repo, head, published=previous)
        result = self.manifest_json(plan, previous, {"web": digest("web")}, "rel-2", "unknown")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("planning", result.stderr)

    def test_unknown_component_rejected(self):
        plan = self.make_plan()
        digests = all_digests()
        digests["gateway"] = digest("gateway")
        result = self.manifest_json(plan, None, digests, "rel-1", "unknown")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("gateway", result.stderr)

    def test_malformed_digest_rejected(self):
        plan = self.make_plan()
        digests = all_digests()
        digests["web"] = f"{ECR}/web:latest"
        result = self.manifest_json(plan, None, digests, "rel-1", "unknown")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("digest", result.stderr.lower())
        digests["web"] = f"{ECR}/web@sha256:zz"
        result = self.manifest_json(plan, None, digests, "rel-1", "unknown")
        self.assertNotEqual(result.returncode, 0)

    def test_non_ecr_image_reference_rejected(self):
        plan = self.make_plan()
        digests = all_digests()
        digests["web"] = "occ-web:local@sha256:" + "ab" * 32
        result = self.manifest_json(plan, None, digests, "rel-1", "unknown")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ecr", result.stderr.lower())

    def test_release_id_must_be_path_safe(self):
        plan = self.make_plan()
        for bad in ("../escape", "a/b", "", ".hidden", "..", "rel..1", "x" * 200):
            result = self.manifest_json(plan, None, all_digests(), bad, "unknown")
            self.assertNotEqual(result.returncode, 0, bad)
            self.assertIn("release id", result.stderr.lower())
        good = self.manifest_json(plan, None, all_digests(),
                                  "main-d625381-run123", "unknown")
        self.assertEqual(good.returncode, 0, good.stderr)

    def test_credential_shaped_input_rejected(self):
        plan = self.make_plan()
        digests = all_digests()
        payload = dict(plan)
        payload["credentialLeak"] = "AKIAIOSFODNN7EXAMPLE"
        result = self.manifest_json(payload, None, digests, "rel-1", "unknown")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("credential", result.stderr.lower())

    def test_previous_plan_without_revision_rejected(self):
        plan = self.make_plan()
        result = self.manifest_json({"inputHashes": plan["inputHashes"]}, None,
                                    all_digests(), "rel-1", "unknown")
        self.assertNotEqual(result.returncode, 0)


class PointerAndStateTests(unittest.TestCase):
    def test_pointer_requires_immutable_reference_fields(self):
        pointer = {"releaseId": "rel-1", "revision": "a" * 40,
                   "releaseKey": "releases/rel-1.json", "version": "v1", "checksum": "sha256:" + "a" * 64}
        release.validate_pointer(pointer)
        with self.assertRaises(release.ContractError):
            release.validate_pointer({"releaseId": "rel-1", "revision": "a" * 40})
        for invalid in (dict(pointer, version="-"), dict(pointer, checksum="-"),
                        dict(pointer, releaseKey="releases/other.json")):
            with self.assertRaises(release.ContractError):
                release.validate_pointer(invalid)

    def test_control_state_validates_modes(self):
        release.validate_control({"desiredMode": "running", "operationId": "op-1",
                                  "phase": "idle", "result": "success"})
        release.validate_control({"desiredMode": "paused"})
        with self.assertRaises(release.ContractError):
            release.validate_control({"desiredMode": "terminated"})
        with self.assertRaises(release.ContractError):
            release.validate_control({"desiredMode": "running", "extra": True})

    def test_cas_rejects_older_and_equal_sequences(self):
        current = {"releaseId": "rel-1", "revision": "a" * 40, "sequence": 5,
                   "releaseKey": "releases/rel-1.json", "version": "v", "checksum": "sha256:" + "a" * 64}
        release.reject_older_overwrite(current, dict(current, sequence=6, releaseId="rel-2", releaseKey="releases/rel-2.json"))
        with self.assertRaises(release.ContractError):
            release.reject_older_overwrite(current, dict(current, sequence=5, releaseId="rel-2"))
        with self.assertRaises(release.ContractError):
            release.reject_older_overwrite(current, dict(current, sequence=4, releaseId="rel-0"))

    def test_cas_rejects_ancestor_revisions_even_with_higher_sequence(self):
        current = {"releaseId": "rel-2", "revision": "b" * 40, "sequence": 5,
                   "releaseKey": "releases/rel-2.json", "version": "v", "checksum": "sha256:" + "a" * 64}
        candidate = dict(current, sequence=9, revision="a" * 40, releaseId="rel-1", releaseKey="releases/rel-1.json")
        with self.assertRaises(release.ContractError):
            release.reject_older_overwrite(current, candidate,
                                           is_ancestor=lambda candidate_rev, current_rev: True)


class CompatibilityEvidenceTests(ReleaseTestCase):
    def test_backward_compatible_requires_evidence(self):
        plan = self.make_plan()
        result = self.manifest_json(plan, None, all_digests(), "rel-1", "backward-compatible")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("evidence", result.stderr.lower())

    def test_evidence_shape_is_validated(self):
        plan = self.make_plan()
        result = self.manifest_json(plan, None, all_digests(), "rel-1", "backward-compatible",
                                    evidence={"rollbackReleaseId": "rel-0"})
        self.assertNotEqual(result.returncode, 0)

    def test_evidence_must_cover_actual_deployed_state(self):
        plan = self.make_plan()
        last_deployed = {"releaseId": "rel-a", "setupFingerprint": "fp-a"}
        covered = release.resolve_migration_compatibility(
            {"rollbackReleaseId": "rel-a", "setupFingerprint": "fp-a",
             "migrations": ["V002__next.sql"]},
            last_deployed, plan["inputHashes"]["db-setup"], ["V002__next.sql"])
        self.assertEqual(covered, "backward-compatible")

    def test_skipped_intermediate_releases_break_coverage(self):
        plan = self.make_plan()
        last_deployed = {"releaseId": "rel-a", "setupFingerprint": "fp-a"}
        covered = release.resolve_migration_compatibility(
            {"rollbackReleaseId": "rel-a", "setupFingerprint": "fp-a",
             "migrations": ["V003__later.sql"]},
            last_deployed, plan["inputHashes"]["db-setup"],
            ["V002__next.sql", "V003__later.sql"])
        self.assertEqual(covered, "unknown")

    def test_changed_setup_fingerprint_means_unknown(self):
        plan = self.make_plan()
        last_deployed = {"releaseId": "rel-a", "setupFingerprint": "fp-old"}
        covered = release.resolve_migration_compatibility(
            {"rollbackReleaseId": "rel-a", "setupFingerprint": "fp-old",
             "migrations": []},
            last_deployed, plan["inputHashes"]["db-setup"], [])
        self.assertEqual(covered, "unknown")

    def test_wrong_rollback_release_means_unknown(self):
        plan = self.make_plan()
        last_deployed = {"releaseId": "rel-b", "setupFingerprint": "fp-a"}
        covered = release.resolve_migration_compatibility(
            {"rollbackReleaseId": "rel-a", "setupFingerprint": "fp-a",
             "migrations": []},
            last_deployed, plan["inputHashes"]["db-setup"], [])
        self.assertEqual(covered, "unknown")

    def test_manifest_stores_audit_evidence(self):
        plan = self.make_plan()
        evidence = {"rollbackReleaseId": "rel-0", "setupFingerprint": plan["inputHashes"]["db-setup"],
                    "migrations": []}
        result = self.manifest_json(plan, None, all_digests(), "rel-1", "backward-compatible",
                                    evidence=evidence)
        self.assertEqual(result.returncode, 0, result.stderr)
        manifest = json.loads(result.stdout)
        self.assertEqual(manifest["migrationCompatibility"], "backward-compatible")
        self.assertEqual(manifest["compatibilityEvidence"], evidence)


class TriggerFixtureTests(ReleaseTestCase):
    """Trigger fixtures for the corrected skip-ci policy."""

    def fixtures(self) -> dict:
        return {
            "infra-only": {"infra/db-setup.sh": "#!/bin/sh\n# v2\n"},
            "workflow-only": {".github/workflows/ci.yml": "name: Core CI v2\n"},
            "setup-only": {"infra/postgres/bootstrap.sql": "SELECT 2;\n"},
            "runtime-config-only": {"infra/nginx/default.conf.template": "server { v2 }\n"},
            "policy-only": {"AGENTS.md": "agent instructions v3",
                            "docs/commit-convention.md": "policy v2"},
            "docs-only": {"docs/README.md": "docs v3"},
            "launcher-only": {"scripts/app.sh": "#!/bin/sh\n# v3\n"},
            "mixed-docs-infra": {"docs/README.md": "docs v4",
                                 "infra/db-setup.sh": "#!/bin/sh\n# v3\n"},
        }

    def test_deployment_affecting_triggers_reach_release_path(self):
        repo = self.make_repo()
        for name in ("infra-only", "workflow-only", "setup-only",
                     "runtime-config-only", "mixed-docs-infra"):
            base = self.git(repo, "rev-parse", "HEAD")
            self.write(repo, self.fixtures()[name])
            head = self.commit(repo, name)
            plan = self.plan_json(repo, head, base)
            self.assertTrue(plan["deployRequired"], name)

    def test_docs_policy_and_launcher_triggers_do_not_release(self):
        repo = self.make_repo()
        for name in ("policy-only", "docs-only", "launcher-only"):
            base = self.git(repo, "rev-parse", "HEAD")
            self.write(repo, self.fixtures()[name])
            head = self.commit(repo, name)
            plan = self.plan_json(repo, head, base)
            self.assertFalse(plan["deployRequired"], name)
            self.assertEqual(plan["buildComponents"], [], name)

    def test_policy_documents_match_classification_behavior(self):
        agents = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
        self.assertIn("deployment-affecting", agents.lower())
        self.assertIn("[skip ci]", agents)
        convention = (ROOT / "docs" / "commit-convention.md").read_text(encoding="utf-8")
        lowered = convention.lower()
        for required in ("deployment-affecting", "workflows", "compose", "dockerfile",
                         "migrations", "scripts/cd"):
            self.assertIn(required, lowered)
        self.assertIn("classify by behavior", lowered)


if __name__ == "__main__":
    unittest.main()
