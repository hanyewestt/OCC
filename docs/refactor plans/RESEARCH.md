# Refactor research and recommended stack

Research date: **15 October 2026 (Asia/Singapore)**. This is a dated research snapshot, not permission to deploy or erase existing data. Recommendations below are project decisions inferred from the supplied design and current repository; linked release and framework facts come from primary sources.

Read [IMPLEMENTATION-PLAN.md](IMPLEMENTATION-PLAN.md) for the executable agent instructions.

**Scope clarification accepted 15 October 2026:** the implementation plan supersedes earlier naming assumptions in this research snapshot. Disruption combines bunching and traffic incident detection under canonical `disruption` service/schema/API names; Ingestion retains source collection/normalization. Planning combines diagnosis and recommendation with no redundant self-HTTP or duplicate mutable context catalog. Live vehicle/captain identity integration, binding and instructions are phase 2 work. Single-host MVP migration with a planned pause is accepted; HA is not a requirement. Relevant build-stack releases were rechecked on 15 October; Maven 3.10.0 is still a release candidate and is excluded.

## 1. What the repository and design actually require

Inputs: `architecture diagram mermaid code.txt`, `database ER diagram mermaid code.txt`, `technical details.txt`, root `AGENTS.md`, `docs/CONVENTIONS.md`, current service POMs, source migrations, local Compose, and `.github/workflows/ci.yml`.

The requested deployment is a **service-based architecture**: three coarse backend services, one routing gateway, one Nginx/static React container, and a shared PostgreSQL instance with isolated schemas. The prose calls it microservices, but the three-service diagram is the more concrete implementation target. Independent service code and schema ownership remain worthwhile on a shared host/database.

| Current working-tree evidence | Refactor implication |
| --- | --- |
| `services/ingestion` | Preserve ingestion capabilities and migration history. |
| `services/operations/bus-bunching-detection` | Generalize into the disruption-owning service; preserve detector behavior. |
| `services/planning/root-cause-diagnosis` and `services/planning/recommendation-engine` | Consolidate into one runnable Planning application, with separate diagnosis/recommendation responsibilities inside it. |
| Existing schemas `ingestion`, `bunching`, `diagnosis`, `recommendation` | Move to `ingestion`, `disruption`, `planning`, through an explicit data migration. |
| Existing Java 25 / Boot 4.1.1 / Spring AI 2.0.1 / springdoc 3.1.1 | Much of the framework stack already matches current stable releases. |
| Maven wrappers and Docker build stages use Maven 3.9.11 | Refresh together after a compatibility build; avoid relying on a developer's global Maven. |
| Compose uses `postgis/postgis:18-3.6` | Preserve spatial support. That tag alone does not prove PostgreSQL patch level or image immutability. |
| CI already runs service verification, oasdiff, and Trivy | Extend and repair this pipeline; the design text saying CI is absent is outdated relative to the working tree. |
| Existing durable retries, checkpoints, retained AI evidence, and database-reduction upgrade tests | The ER diagram is incomplete as a physical schema specification. Do not remove these capabilities just because their tables/columns are not pictured. |
| No frontend `package.json` found during inspection | React is new implementation work; existing ingestion-hosted demo resources need inventory before removal. |

There are substantial pre-existing uncommitted edits, deletions, and untracked files. They were inspected as the baseline, not modified by this research. Do not reset, clean, or overwrite them when executing the plan.

## 2. Stable releases and project selections

Use released artifacts, not milestones, RCs, snapshots, React canaries, or floating Docker tags. Recheck the links when implementation starts; record exact versions, image digests, Maven distribution checksums, and action commit SHAs in a version manifest. A newer independent library release is not automatically a compatible Spring Boot dependency.

| Area | Official release evidence checked | Recommended selection / rationale |
| --- | --- | --- |
| Java | Java 27 is released; Java 25 is the current LTS. [Java specifications](https://docs.oracle.com/javase/specs/), [Oracle LTS information](https://blogs.oracle.com/java/jdk-21-approaches-end-of-permissive-license) | Retain **Java 25**, with patched Eclipse Temurin JDK/JRE builds pinned by digest. Boot 4.1.1 documents compatibility through Java 26, so Java 27 is not the default here. [Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html) |
| Spring Boot | **4.1.1** stable; 4.2 is preview. [Release documentation](https://docs.spring.io/spring-boot/) | Retain 4.1.1; use its parent/BOM for managed dependencies. |
| Spring Cloud | **2025.1.3** stable; 2025.1 supports Boot 4.1 starting at 2025.1.2. [Compatibility matrix](https://spring.io/projects/spring-cloud/), [2025.1.3 release](https://github.com/spring-cloud/spring-cloud-release/releases/tag/v2025.1.3) | Import 2025.1.3 BOM in the gateway only. Do not select preview 2026.0 merely because the year is newer. |
| API gateway | Cloud 2025.1.3 includes **Gateway 5.0.3**. [Release contents](https://github.com/spring-cloud/spring-cloud-release/releases/tag/v2025.1.3) | Use **Server Web MVC**, artifact `spring-cloud-starter-gateway-server-webmvc`; fits existing blocking MVC/JPA services and servlet security. [Official starter](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webmvc/starter.html) |
| Apache Maven | **3.9.16** is the latest stable GA; 3.10.0-rc-1 is a preview. [Download page and current stable/preview status](https://maven.apache.org/download.cgi), [release history](https://maven.apache.org/docs/history) | Use 3.9.16 for production builds; verify the distribution checksum. Do not put a release candidate in the shared wrapper. |
| Maven Wrapper | **3.3.4** is the current stable wrapper release. [Apache download page](https://maven.apache.org/tools/wrapper/download.cgi), [wrapper checksum properties](https://maven.apache.org/tools/wrapper/index.html) | Use the `only-script` wrapper distribution with `distributionSha256Sum`; generate one root wrapper and validate both PowerShell and POSIX launchers. |
| PostgreSQL | Official version policy currently lists **18.6** as the latest supported 18 patch. [Version policy](https://www.postgresql.org/support/versioning/) | Retain 18.6 for this refactor. Keep the same major in tests. Verify actual image binary versions rather than guessing from `18-3.6`. |
| PostGIS | 3.7 is shown as an RC; release notes reference stable **3.6.4**. [PostGIS news](https://postgis.net/news/) | Retain stable 3.6 line, minimum 3.6.4; resolve a PostgreSQL 18.6 compatible image/digest and record `postgis_full_version()`. No spatial-library removal. |
| Persistence/security/testing libraries | Boot 4.1.1 managed coordinates include Flyway **12.4.0**, Testcontainers **2.0.5**, JUnit Jupiter **6.0.3**, Mockito **5.23.0**. [Boot dependency inventory](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html) | Let Boot manage Spring Security, Hibernate, JDBC, Jackson, Flyway, JUnit, Mockito, and Testcontainers. These are compatible managed selections, not claims that each is its globally newest release. |
| OpenAPI | **springdoc 3.1.1** current documentation. [springdoc](https://springdoc.org/) | Keep 3.1.1 and OpenAPI 3.1 per repository conventions; export public and internal groups. Springdoc is a community project, not a Spring-maintained module. |
| Architecture tests | **ArchUnit 1.5.0**, already present. [Maintainer repository](https://github.com/TNG/ArchUnit) | Keep `archunit-junit6`; extend existing rules to service and module boundaries. |
| Coverage | **JaCoCo 0.8.15** stable download. [Downloads](https://www.jacoco.org/jacoco/index.html) | Keep 0.8.15; do not mistake development/trunk 0.8.16 artifacts for stable. |
| Test execution | Repository uses **Surefire/Failsafe 3.5.6**; newer 3.6 milestones appear in docs. [Surefire](https://maven.apache.org/surefire/), [stable source distribution](https://downloads.apache.org/maven/surefire/) | Retain a matched 3.5.6 pair unless the setup check establishes a newer GA pair; never use milestone plugins for this refactor. |
| Spring AI | **2.0.1** stable and already used. [Spring AI reference](https://docs.spring.io/spring-ai/reference/) | Keep Spring AI BOM 2.0.1 and existing Google GenAI adapters initially. Preserve provider types inside adapters. [Google adapter](https://docs.spring.io/spring-ai/reference/api/chat/google-genai-chat.html) |
| Direct Google Java SDK | **java-genai 1.76.0** GA release. [Maintainer release](https://github.com/googleapis/java-genai/releases/tag/v1.76.0) | Research option, not an additional required dependency. Do not override Spring AI's transitive SDK arbitrarily or replace working adapters during consolidation. |
| Gemini models | Model catalogue lists newer stable Gemini 4 Argon / 3.8 Flash, 3.5 Flash-Lite and existing 3.1 Flash-Lite. [Official model catalogue](https://ai.google.dev/gemini-api/docs/models) | Keep configured **`gemini-3.1-flash-lite`** during migration; evaluate 3.5 Flash-Lite as a separate candidate for this bounded classification/recommendation workload. Record availability, cost, quality and latency before changing the default. No preview model. |
| Docker Engine | **29.9.0**, released 8 October 2026. [Engine release notes](https://docs.docker.com/engine/release-notes/29/) | Current stable Linux Engine for EC2/CI compatibility validation; Windows development uses Docker Desktop's supported Linux-container backend. |
| Docker Compose | **5.6.0** stable. [Maintainer release](https://github.com/docker/compose/releases/tag/v5.6.0) | Use supported `docker compose` plugin, not legacy Python `docker-compose`. Test Testcontainers/Engine compatibility during setup. |
| Nginx | **1.30.5** stable vs 1.31 mainline. [Downloads](https://nginx.org/en/download.html) | Choose stable 1.30.5 image by digest; verify required unprivileged/container packaging is available. |
| Node.js | **24.21.0** latest LTS; newer Current line also exists. [Downloads](https://nodejs.org/en/download/) | Node 24 LTS build/test runtime, pinned; keep Node out of the serving container. |
| React | Current docs identify stable **19.3**. [Versions](https://react.dev/versions) | React 19.3 line, exact released `react`/`react-dom` matching patch in lockfile; static client SPA. No SSR framework required. |
| Vite | **8.3.4** stable. [Maintainer release](https://github.com/vitejs/vite/releases/tag/v8.3.4) | Vite 8.3.4; prove chosen React plugin and Node compatibility before committing lockfile. |
| TypeScript | **7.0.2** stable release. [Maintainer release](https://github.com/microsoft/TypeScript/releases/tag/v7.0.2) | Evaluate 7.0.2 with selected React/Vite lint tooling; native compiler packaging changed, so verify package/binary installation rather than blindly copying older `tsc` setup. Record any supported compatibility pin. |
| Supabase frontend SDK | **supabase-js 2.117.3** stable. [Maintainer release](https://github.com/supabase/supabase-js/releases/tag/v2.117.3) | Use for sign-in/session refresh; only URL and publishable key reach the browser. Backend JWT verification uses Spring Security, not a Java Supabase SDK. |
| Frontend unit tests | **Vitest 5.0.4** stable. [Maintainer release](https://github.com/vitest-dev/vitest/releases/tag/v5.0.4) | Vitest plus Testing Library, with compatible released React/DOM adapter versions pinned during setup. |
| Browser tests | **Playwright 1.64.0** stable. [Maintainer release](https://github.com/microsoft/playwright/releases/tag/v1.64.0) | Pin package and matching browser/container versions together. Test through Nginx and gateway. |
| Image/repository scan | **Trivy 0.75.0** stable. [Maintainer release](https://github.com/aquasecurity/trivy/releases/tag/v0.75.0) | Pin scanner binary/image independently from action SHA; scan final images, dependencies, secrets and Compose configuration. |
| API compatibility | **oasdiff 1.33.0** stable; 1.33.1 is RC. [Maintainer releases](https://github.com/oasdiff/oasdiff/releases) | Upgrade current 1.32.1 tooling only after validating CLI/image commands; compare generated contracts against a manifest of old contract paths. |
| Load tests | **k6 2.3.0** latest mainline GA; 1.8.2 is a newer maintenance release of the older line. [Maintainer releases](https://github.com/grafana/k6/releases) | Use 2.3.0 with released APIs, not experimental features; short deterministic checks in CI, full soak on representative staging hardware. |

For managed services, there is no package-version pin for EC2, SSM, ECR, S3, Secrets Manager, CloudWatch, or hosted Supabase Auth. Record region, resource identifiers, account/project settings, key algorithms, API assumptions, deployment date, AMI identifier, and cost/operational ownership instead.

## 3. Service architecture practices to adopt

These are the recommended application of official framework features to OCC, not an assertion that Spring prescribes this exact service topology.

### Code and dependencies

- Keep MVC/JPA for backend services and MVC gateway. Do not mix WebFlux and blocking repositories in the same application, or add reactive gateway examples to the MVC starter.
- Preserve repository layering, records, constructor injection, injected `Clock`, UTC `Instant`, typed validated configuration, and DTO boundaries. Extend ArchUnit tests already in this tree.
- Use Boot-managed dependencies and explicit Cloud/AI BOMs. Confirm compatibility with effective POM and dependency tree; prefer Boot 4 module-specific starters and test modules over legacy imports.
- Use `RestClient` for blocking HTTP adapters; typed HTTP interfaces may live in infrastructure and implement domain ports. Framework 7 deprecates `RestTemplate`. [Spring REST client guidance](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html)
- Use short database transactions around local state changes. Do not hold database locks while waiting for DataMall, Gemini, or another service. Durable requests/outboxes and idempotency cover retries; do not claim distributed exactly-once delivery.

### Database ownership

Each service uses its own schema and runtime account; migrations have separate privileged credentials. PostgreSQL schemas alone do not establish a security boundary: privileges and `search_path` must also be controlled. Cross-service identifiers/evidence references are API-level links, not cross-schema JPA relationships. [PostgreSQL schema and search-path guidance](https://www.postgresql.org/docs/18/ddl-schemas.html)

Retain new schema histories separately from old applied Flyway histories. Additive migrations first; copy and reconcile under a bounded write pause for the final cutover. Never copy two independent migration histories into one and hope duplicate `V001` names work. [Flyway migration documentation](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/versioned-migrations)

### Authentication and authorization

Use an asymmetric Supabase signing key and configured issuer/JWKS/audience/algorithm checks. Resource servers validate signature and time claims; include explicit audience validation. Validate JWTs at gateway and backend public endpoints. Gateway authorization is coarse; domain authorization decides which actor can access or change a particular record. [Spring Security JWT resource server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)

Supabase's default `role` is a PostgreSQL role, not OCC's `ADMIN`/`CONTROLLER`/`DRIVER`. Add a trusted custom OCC role claim through a controlled administrative process/Access Token Hook. Never authorize from user-editable metadata. Signing-key rotation and the delay until role changes reach refreshed JWTs need tests and a runbook. [Supabase JWTs](https://supabase.com/docs/guides/auth/jwts), [signing keys](https://supabase.com/docs/guides/auth/signing-keys), [custom claims/RBAC](https://supabase.com/docs/guides/api/custom-claims-and-role-based-access-control-rbac)

Internal endpoints require distinct service identity and explicit capabilities; a private network is an additional boundary. Do not forward browser-controlled identity headers into trusted server code. Block internal APIs and management endpoints at both Nginx and gateway. The captain's read-only view also needs server-side ownership filtering.

### Runtime, testing and CI

Bind operational settings through records with `@ConfigurationProperties`, validate on startup, and maintain `.env.example`. [Boot external configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)

Use liveness for application viability and readiness for serving capability. Do not include shared/external providers in liveness. Expose probes internally and document what readiness depends on; do not restart all JVMs just because Gemini is unavailable. [Boot endpoint/probe guidance](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html)

Use real PostgreSQL/PostGIS Testcontainers with `@ServiceConnection`; align container and cached Spring context lifetimes. Prove migrations both from empty state and current legacy state. CI must reject silently skipped integration tests. [Boot Testcontainers](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html)

Use a production Compose definition with immutable built images, explicit networks/volumes, controlled publication, health checks, and restart/resource limits. Compose is suitable for the diagram's single-host deployment but does not provide multi-host HA. [Docker production Compose](https://docs.docker.com/compose/how-tos/production/)

Use least-privilege GitHub job permissions, immutable action SHA pins, isolated untrusted PR jobs, and no production secrets in tests. [GitHub secure-use guidance](https://docs.github.com/en/actions/reference/security/secure-use)

### AI and AWS

Structured JSON output is one validation layer; apply deterministic domain checks to evidence, target buses/stops, limits and allowed actionability. Preserve failure/fallback/input/output records across migration. Evaluate model upgrades independently. [Gemini structured outputs](https://ai.google.dev/gemini-api/docs/structured-output)

Keep the diagram's EC2 + Compose deployment. Prefer SSM Session Manager administration, only required inbound network access, scoped instance profiles, encrypted persistent storage and tested off-host backups. [EC2 security groups](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-security-groups.html), [SSM Session Manager](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager.html)

Inject secrets at runtime from the deployment secret store. Resolve references in the deployment process without printing values or placing them in AI context; Compose does not natively interpret AWS Secrets Manager references. [AWS retrieval documentation](https://docs.aws.amazon.com/secretsmanager/latest/userguide/retrieving-secrets.html)

## 4. Deliberately excluded from the initial refactor

No Kubernetes/EKS, ECS migration, Kafka/RabbitMQ, Redis, Eureka, Config Server, service mesh, native-image build, vector database, new AI agent framework, or managed RDS migration. None is required by the supplied design. PostgreSQL-backed durable recovery plus bounded HTTP interactions suffice for the existing workflow. Reconsider these only against measured requirements, as separate architecture decisions.

Production availability remains bounded by one EC2 host and one PostgreSQL instance. State this explicitly; do not describe container health checks as an HA strategy.
