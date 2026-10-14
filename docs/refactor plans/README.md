# Refactor package

Prepared **14 October 2026** from the supplied architecture, database diagram, technical details, current working tree and official release/framework documentation.

Plan reorganized **15 October 2026** into two core phases; release research remains dated 14 October.

The ten design-gap decisions are now accepted from the user's feedback: canonical Disruption combines bunching and traffic incident detection; canonical Planning combines diagnosis and recommendation; live identity integration is deferred to phase 2; single-host MVP migration is acceptable and HA is outside scope.

1. Read [RESEARCH.md](RESEARCH.md) for current stable releases, compatible project selections, official sources and repository findings.
2. Follow [IMPLEMENTATION-PLAN.md](IMPLEMENTATION-PLAN.md) for two core phases with detailed work packages, ownership/migration decisions, setup/build instructions, security rules, CI/test gates, deployment/rollback and separate acceptance matrices.
3. Use the phase-specific handoff prompts at the end of the plan. The default prompt implements phase 1 and stops before phase 2.

**Phase 1 — Refactor first:** migrate bunching and traffic incident detection into **Disruption** (`/api/v1/disruption/**`), combine diagnosis/recommendation into one new **Planning** application, remove redundant self-HTTP and duplicate mutable Planning context sources, and preserve Ingestion and existing demo/workflow behavior. Migrate schemas/evidence/recovery state safely, add gateway/authentication/authorization, extend security/testing/CI and verify both detection paths plus existing diagnosis/recommendation behavior. Use a planned-pause MVP migration with deployment/restore/rollback procedures; HA is not required. New product UI and feature tables/endpoints are not required.

**Phase 2 — New/additional features later:** integrate authoritative vehicle/captain identity data when its source is available and validated, and add approval/rejection, authenticated captain binding, assigned instructions and controller/captain React interfaces with their security/testing/release checks. Identity availability does not automatically authorize live dispatch. Start after phase 1 passes and feature work is requested.

Development Supabase setup now has a [step-by-step checklist and role-hook SQL](SUPABASE-SETUP.md) for phase-1 integration through a temporary login page. Synthetic JWT/JWKS fixtures remain the CI baseline. Product login pages and deployed callbacks remain phase 2 work; public deployment waits for verified production identity configuration.

The target configuration uses canonical supported values only. All deprecated/unused values and runtime aliases are removed as an explicitly approved configuration migration; old-to-new names are documented for operators rather than accepted by the refactored services.

Each phase also removes all confirmed unused files. Phase 1 ends with a dedicated repository cleanup and verification gate: obsolete services, duplicate code/resources, unused configuration, stale artifacts and empty scaffolding are removed. Code rollback uses recorded Git commits; active Flyway migrations and database backup/recovery procedures remain necessary for data migration safety.

Setup/build uses existing tools directly: one root `compose.yaml` with small environment overrides, one root Maven wrapper/reactor, one shared Java Dockerfile, one database role/bootstrap SQL file, and per-service Flyway jobs/migrations. The plan removes the old init shell hierarchy and the proposed collection of verification wrapper scripts; checks run through Maven, Compose and the frontend's own npm commands.

The recommended target is **three backend services (Ingestion, Disruption, Planning), one Spring Cloud Gateway application, static React served by Nginx, and one PostgreSQL/PostGIS instance with separate service schemas/accounts**. Diagnosis and recommendation become responsibilities inside Planning.

The plan preserves Java 25 LTS, Spring Boot 4.1.1, Spring AI 2.0.1, PostgreSQL 18.6 and springdoc 3.1.1. It adds compatible stable Spring Cloud 2025.1.3 / Gateway 5.0.3 and dates the independent frontend, container and test-tool selections. Latest available and recommended project versions are distinguished where compatibility or evaluation matters.

Record the accepted decisions and remaining implementation details in ADRs:

- Canonical `disruption` schema/service/API namespace replacing legacy bunching names, with both detector responsibilities.
- Recovery tables and AI input/output evidence missing from the logical ER diagram.
- Captain identity binding, approval decisions and assigned instructions missing from the ER diagram: deferred phase-2 decisions, not blockers for the core refactor.
- Current normalized/derived fields that differ from the pasted ER.
- Compatibility, write-pause cutover and rollback after new data has been written.

These documents are a researched draft, not evidence of executed refactoring or production deployment. Existing application files, CI and uncommitted work were left untouched. Proposed scripts and profiles in the plan must be implemented before they can be run.

## Original inputs

- [Architecture Mermaid](architecture%20diagram%20mermaid%20code.txt)
- [Database Mermaid](database%20ER%20diagram%20mermaid%20code.txt)
- [Technical details](technical%20details.txt)
- `architecture diagram.png`
- `database ER diagram.png`
