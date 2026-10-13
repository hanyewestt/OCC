# CI implementation instructions

Follow the repository-root AGENTS.md and docs/CONVENTIONS.md.
These instructions guide work on GitHub Actions under this directory.

## Goal

Automatically check pull requests and report build and test results on GitHub.
The user wants results only for now. Do not add required merge checks or change
branch protection. A failed job must still be reported as failed.

## First implementation

- Use GitHub Actions with Java 25 and a Linux runner with Docker available.
- Run on pull requests, pushes to the repository's actual default branch, and
  manual workflow dispatch. Resolve the default branch before authoring triggers.
- Check these services independently, using their own Maven wrappers:
  - services/ingestion
  - services/operations/bus-bunching-detection
  - services/planning/recommendation-engine
  - services/planning/root-cause-diagnosis
- Run `./mvnw --batch-mode --no-transfer-progress clean verify` in each service.
  Make the wrapper executable in the runner when needed.
- Verify that unit/controller tests use Surefire and integration tests use
  Failsafe. Add missing lifecycle bindings when existing integration tests need
  them. Report absent test coverage rather than claiming it was tested.
- Confirm Docker is available before integration tests. Existing tests may skip
  when Docker is unavailable; those skips must not become a misleading pass.
- Build each service's existing Dockerfile after its Maven verification passes.
  Dockerfiles currently skip tests, so image builds do not replace verification.
- Cache Maven dependencies. Keep service result names stable and let other
  service jobs finish if one fails.
- Upload available Surefire and Failsafe reports even when a check fails.
- Start with all four services on every pull request. Add change filtering only
  when shared files and required checks are handled correctly.

## Credentials and scope

- Use temporary test databases and controlled fixtures or stubs. CI must not
  require the developer's .env, live DataMall credentials, Gemini keys, or AWS.
- Do not publish images, deploy services, or modify Jira as part of this phase.
- Use minimum workflow permissions, normally contents: read. Use pull_request,
  not pull_request_target, to execute proposed code.
- Pin third-party actions to reviewed commit SHAs and document their versions.
- Keep deployment settings consistent with the root configuration rules.

## Completion evidence

- Document triggers, checks, local verification commands, and where to find
  results in the repository README.
- Validate workflow syntax and run relevant verification where possible.
- Explain any failing tests, skipped tests, or unavailable checks clearly.
- Do not claim CI is working on GitHub until a real workflow run confirms it.
- Report the files changed and verification performed. Branch protection remains
  a separate future decision.
