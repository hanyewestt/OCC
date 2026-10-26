
# Agent Instructions

## Engineering Standards

- Deliver final, durable fixes rather than temporary workarounds.
- Write minimal, efficient code. Prefer built-in operators, standard library
  functions, and existing utilities over new classes, methods, or abstractions
  that do the same thing. For example, use `x + y` instead of an `add(x, y)`
  wrapper. Add abstractions only when they provide meaningful behavior or clarity.
- Follow [docs/CONVENTIONS.md](docs/CONVENTIONS.md) for all service work.
- Follow [docs/commit-convention.md](docs/commit-convention.md) for commits.
  Append `[skip ci]` to the commit subject when no service or frontend code is
  edited, including documentation-only and local development launcher changes.
  Still run checks relevant to the changed files.
- Before reporting work complete, run the relevant automated test suites and
  make sure all applicable tests pass. Investigate every failed or skipped test;
  fix failures and determine whether skips are intentional, documenting any
  remaining justified skips.
- Verify behavior with the relevant automated tests in the codebase and by
  exercising the feature in the running application. Report what was exercised
  and any verification that could not be completed.

## Frontend Standards

- Follow [docs/frontend-convention.md](docs/frontend-convention.md) for all
  frontend work. Keep detailed frontend rules in that document rather than
  duplicating them here.
- Use React, TypeScript, Vite, Tailwind CSS and shadcn/ui. Reuse existing
  components and shadcn components before creating custom UI.
- Keep the MVP simple: add folders, dependencies and abstractions only when
  required by implemented behavior. Keep designs minimalist and accessible.
- Build static assets with Vite and serve them through Nginx in production.
  Verify routing and API proxy compatibility through Nginx.
- Enforce permissions in backend APIs as well as frontend navigation. Never
  expose secrets in browser code or build output.
- Run lint, TypeScript checking, the production build and tests relevant to the
  change before reporting frontend work complete.

## Configuration

- Treat every value that can vary by deployment or may need to change without a
  code release as external configuration. This includes URLs, ports, database
  connection settings, credentials, API keys, timeouts, retry delays, schedules,
  polling intervals, thresholds, retention periods, feature flags, batch sizes,
  and operational limits.
- Use environment variables as the deployment interface. Bind service settings
  through typed `@ConfigurationProperties`; do not introduce hard-coded Java
  constants or `@Value` fields for operational settings.
- Keep one local inventory in the repository-root `.env.example`. Every supported
  environment variable must be named, described, and given a non-secret example
  or safe documented default there. The real `.env` remains ignored; deployment
  supplies the same values through its environment and secret/config store.
- Every configurable value in `application.yml` or a profile-specific YAML file
  must be expressed through an environment-variable placeholder and represented
  in `.env.example`, including placeholders with fallback defaults such as
  `${OCC_AREA_LIMIT:100}`. A fallback is allowed only when intentional, safe, and
  documented; it is not a substitute for adding the variable to the central
  inventory.
- `application.yml` may retain only stable application structure, true invariants,
  and safe documented defaults. It must never contain credentials, API keys, or
  deployment-specific endpoints. Do not turn a true internal invariant into a
  runtime knob merely to satisfy this rule.
- When adding or changing a runtime setting, update its environment binding,
  `.env.example`, and the relevant README in the same change. Preserve existing
  environment-variable compatibility unless an explicit migration is approved.
- Use descriptive configuration names that state both purpose and network boundary.
  Do not reuse one URL or port variable for host/public and container/internal
  traffic. Name host-facing settings `*_PUBLIC_URL` or `*_PORT`, and name
  Compose-only settings `*_INTERNAL_URL` or `*_INTERNAL_PORT`.
- Allocate core-service ports consistently: public host ports start at `6767` and
  increment by one for each subsequent core service (`6767`, `6768`, ...);
  corresponding container-internal ports start at `8001` and increment by one
  (`8001`, `8002`, ...). Compose services communicate through service DNS and
  internal ports, never through host-published ports. Keep supporting-service
  host ports at their conventional defaults (for example PostgreSQL on `5432`),
  except pgAdmin, which is published on `6700`.

## API Changes

- When an API changes, regenerate and update its OpenAPI contract to match the implementation.

## References

- [The Twelve-Factor App: Config](https://12factor.net/config)
- [Spring Boot externalized configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)
