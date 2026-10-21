# Deployment and container topology

## Local topology

The default `compose.yaml` and automatically loaded `compose.override.yaml`
start PostgreSQL/PostGIS, one bootstrap job, three Flyway jobs, Ingestion,
Disruption, Planning, Gateway and Nginx. The optional `tools` profile adds
pgAdmin. See the [development guide](DEVELOPMENT.md) for build/start commands.

Nginx serves the prebuilt `frontend/dist/` and forwards `/api/v1/**` through Gateway while
preserving bearer authentication. It blocks internal and management routes.
Local application ports are loopback-only: Ingestion 6767, Disruption 6768,
Planning 6769 and Gateway 6770. Corresponding container ports are 8001-8004;
Compose calls use service DNS and internal ports. Gateway owns no business DB.

The base Nginx service has an optional `edge` profile so explicit backend test
stacks can omit it. Local and production overrides activate it automatically
using a profile reset; Docker Compose 2.24.4 or newer is required.

The root Maven Wrapper and `infra/java.Dockerfile` build all four applications.
Each schema has a separate migration image built with `infra/flyway.Dockerfile`.
Migration images retain PostgreSQL JDBC support and omit unrelated Databricks,
Couchbase and SQL Server drivers.

## Production HTTPS configuration

Use explicit Compose files so local development ports/build overrides are not
merged into deployment. Before running, supply immutable registry digests for
all application/migration images and operator-managed TLS certificate files.
CI builds and scans images; a passing build does not itself publish or deploy them.

PowerShell and Bash use the same commands:

```shell
docker compose --env-file .env -f compose.yaml -f infra/compose.production.yaml config -q
docker compose --env-file .env -f compose.yaml -f infra/compose.production.yaml up -d
```

Required deployment inputs include `OCC_NGINX_SERVER_NAME`,
`OCC_NGINX_TLS_CERTIFICATE_FILE`, `OCC_NGINX_TLS_PRIVATE_KEY_FILE`,
`OCC_NGINX_PUBLIC_PORT` (default 443), and the `OCC_*_IMAGE` /
`OCC_*_MIGRATION_IMAGE` registry digests. Keep certificates and credentials out
of Git. The ignored root `.env` is for local configuration; deployment must
supply its own protected environment/settings.

Local HTTP uses `infra/nginx/local.conf.template`. Production HTTPS uses
`infra/nginx/default.conf.template`. Both include the shared routes generated
from `infra/nginx/routes.inc.template`. Build with `npm ci` and `npm run build`
in `frontend/` before starting Compose; stage `dist/` alongside the deployment files. Production serves
the React login/controller/captain routes and blocks retired demo/auth pages. See
[authentication](AUTHENTICATION.md) for the unfinished production-login boundary.

## Configuration reference

Use `.env.example` as the single inventory. Local HTTP is controlled by
`OCC_NGINX_PORT` (default 80). Container listeners use
`OCC_NGINX_HTTP_INTERNAL_PORT` / `OCC_NGINX_HTTPS_INTERNAL_PORT` (8080 / 8443).
DNS cache validity uses `OCC_NGINX_DNS_VALIDITY` (30s); request sizes, rate limits
and proxy timeouts have their own inventoried settings.

Actual AWS deployment, production DNS/certificate issuance and renewal, and
external deployment qualification remain deferred. Local disposable-certificate
checks do not establish a deployed HTTPS service. Before deployment, choose the
host, access controls, image publication method, protected configuration and
backup/restore procedure; verify startup and the normal workflow on that host.
