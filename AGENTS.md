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

- Externalize deployment-specific values and intentional operational controls;
  keep internal application wiring and invariants in code.
- Use documented environment variables for ordinary deployment settings and the
  deployment's secret mechanism (such as mounted secrets or secret stores) for
  credentials.
- Follow [docs/CONVENTIONS.md](docs/CONVENTIONS.md) for typed binding, validation,
  defaults, naming, and networking.
- Document project-supported settings in the repository-root `.env.example` using
  non-secret examples; keep the real `.env` ignored. Do not document internal
  algorithm heuristics, math constants, buffer limits, domain invariants, or
  generic framework properties there; keep those as defaults in Java records or
  application configuration.
- Validate required settings at startup. Use safe, documented defaults for optional
  settings.
- Update configuration documentation when settings change and preserve existing
  environment-variable compatibility.

## API Changes

- When an API changes, regenerate and update its OpenAPI contract to match the implementation.

## References

- [The Twelve-Factor App: Config](https://12factor.net/config)
- [Spring Boot externalized configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)
- [Docker secrets in Compose](https://docs.docker.com/compose/how-tos/use-secrets/)
- [Docker Compose networking](https://docs.docker.com/compose/how-tos/networking/)
