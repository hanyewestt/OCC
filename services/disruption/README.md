# OCC Disruption

Disruption is the canonical service for bunching detection and traffic incident
detection. The service owns the canonical `disruption` schema and exposes both
bunching alerts and traffic incidents through the same API and security boundary.
Legacy bunching data is migrated by the checked-in Flyway migration; the old
service and schema are not runtime dependencies.

From the repository root, verify the service with the root Maven Wrapper:

```powershell
.\mvnw.cmd -pl services/disruption verify
```

```bash
./mvnw -pl services/disruption verify
```

For a host-native local run, create the ignored root `.env` using
[`.env.example`](../../.env.example), then start the local PostgreSQL stack from
the root using [the local setup guide](../../infra/local/README.md). Run the app
from its service directory so `application-local.yml` imports the root `.env`:

```powershell
Set-Location services/disruption
..\..\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'
```

```bash
cd services/disruption
../../mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The service binds to `OCC_DISRUPTION_INTERNAL_PORT` in Compose and uses
`OCC_DISRUPTION_PORT` for host-facing development access. Its PostgreSQL and
peer URLs use distinct internal/public variables so Compose traffic uses service
DNS while host-native runs use `localhost`.

To refresh the checked-in OpenAPI contract, start the local Compose stack, put a
short-lived ADMIN or CONTROLLER access token in `OCC_OPENAPI_EXPORT_TOKEN` for the
current shell only, then run from the repository root:

```powershell
$env:OCC_OPENAPI_EXPORT_TOKEN = '<short-lived access token>'
.\mvnw.cmd -pl services/disruption -Popenapi org.springdoc:springdoc-openapi-maven-plugin:1.5:generate "-Dopenapi.apiDocsUrl=http://localhost:6768/v3/api-docs"
Remove-Item Env:OCC_OPENAPI_EXPORT_TOKEN
```

This updates `services/disruption/openapi/disruption.json` from the running API.
Never save the token in `.env` or commit it.
