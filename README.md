# OCC (Semi-autonomous Operations Control Center)

An agentic dispatch and incident mitigation system for Singapore public bus operations (SMRT / SBS Transit / LTA Bus 4.0) that correlates live transit telemetry, road incidents, weather hazards, and rail disruptions to facilitate orchestration of real-time fleet adjustments. Each Spring Boot service owns its code, database schema and migrations.

## Local quick start
Read [this](infra/local/README.md)

## Automatic CI checks

The `OCC CI` GitHub Actions workflow checks all four services when a pull request
is opened or updated, when code is pushed to `main`, and when started manually.
Each service runs separately with Java 25 and Docker:

1. Compile and run unit/controller tests and existing database integration tests.
2. Check the test reports for failures, missing execution, and skipped integration tests.
3. Build the service's Docker image after verification succeeds.

Open the pull request's **Checks** tab or the repository's **Actions** tab. Each
service has its own result, a test summary, and downloadable test reports. Failures
are reported normally; this change does not make checks required for merging.
Images are built for verification only; they are not published or deployed.

Tests use fixtures and temporary Testcontainers databases, without the local
`.env`, DataMall/Gemini keys, or AWS credentials. Maven dependencies are cached.
Detection database tests disable background delivery workers so they do not
contact other services or keep running after the temporary database closes.
The third-party actions are pinned to specific commits, with release versions
beside the pins in `.github/workflows/ci.yml`.

### Run the same checks locally

Start Docker and use Java 25. In each service directory run:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
docker build --tag occ-service:ci .
```

On Linux/macOS, use `chmod +x mvnw` and `./mvnw` instead. Run the report checker
from the repository root using Python 3, for example:

```text
python .github/scripts/check_test_reports.py services/ingestion
```

Current coverage: ingestion, bus-bunching-detection, and recommendation-engine
have database integration tests. Root-cause-diagnosis currently has controller
tests only; its job reports the missing integration coverage. CI does not yet
provide full API compatibility checks, deployment, or image vulnerability scans.
A successful local check does not confirm execution on GitHub; the first pull
request workflow run provides that evidence.
