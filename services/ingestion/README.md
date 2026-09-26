# OCC Ingestion Service

Polls DataMall bus arrivals and records observations for analytics. Requires Java 25, PostgreSQL with the `ingestion` schema and `occ_ingestion` account, and a valid DataMall key.

A failed DataMall reference refresh does not stop the HTTP API or replace stored
topology. If there is no imported topology, target seeding is deferred and the
polling schedule retries the import. A DataMall `401` means the configured key
was rejected; update `DATAMALL_API_KEY` in the ignored root `.env` and recreate
the Ingestion container so it receives the new value.

## Run only ingestion

From the repository root:

```powershell
cd services/ingestion
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'
```

```bash
cd services/ingestion
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

A global Maven installation is optional; the wrapper downloads Maven on first use.

The `local` profile imports `../../.env`, the repository's single local configuration file. Create it from the root `.env.example` if needed. Relevant values are:

```dotenv
OCC_INGESTION_DB_PASSWORD=your-database-password
DATAMALL_API_KEY=your-datamall-key
```

Use unquoted values, without `export`. The default local URL uses `POSTGRES_PORT` (5432) and `POSTGRES_DB` (occ). Override it with `OCC_INGESTION_DB_URL` or `SPRING_DATASOURCE_URL` for an existing external database. The password must match the account already created in that database.

PostgreSQL must be running before the app starts. To start the shared local database, run this from the repository root:

```powershell
docker compose --env-file .env -f infra/local/compose.yaml up -d
```

```bash
docker compose --env-file .env -f infra/local/compose.yaml up -d
```

This does not start operations or planning. See [database setup](../../infra/local/README.md).

## IDE and deployment

Import `pom.xml` as a Maven project. Run `com.csd.occ.ingestion.IngestionApplication` with the `local` profile and working directory `services/ingestion`.

Without the `local` profile, the app does not read the root `.env`; supply deployment configuration through environment variables. Custom properties use `occ.datamall.*`. The DataMall API key remains supplied through `DATAMALL_API_KEY`.

```powershell
.\mvnw.cmd verify
docker build -t occ-ingestion .
```

```bash
./mvnw verify
docker build -t occ-ingestion .
```

Build the JAR before building the image. The Dockerfile copies only the packaged JAR, not local credentials. Runtime database and API credentials must be provided separately.

## Startup behavior and API

Flyway applies pending ingestion migrations, then Hibernate validates entity mappings. Startup imports versioned DataMall bus stops, services and route topology. It then creates only the missing polling targets for `occ.ingestion.monitored-services` (190 and 972 by default). `BusArrivalSchedule` polls each enabled stop every minute. Each successful poll keeps the original response in `raw_observation`, writes one normalized row per service and NextBus position to `bus_arrival_observation`, and updates `feed_status`. Retention removes raw and normalized observations after `occ.ingestion.retention` (24 hours by default); logs remain on the terminal.

If the active reference topology is absent, the scheduler first retries the reference import and target seeding. If that import fails, it skips the poll cycle without sending per-stop arrival requests; the terminal log records the single import failure.

### Polling coverage and bus-bunching coverage

Ingestion, rather than the bus-bunching detector, decides which stops are covered.
The setting is `occ.ingestion.monitored-services`, configured locally through the
`OCC_INGESTION_MONITORED_SERVICES` environment variable. Its default is `190,972`:

```dotenv
# Comma-separated DataMall service IDs. Ingestion polls every imported route stop
# served by at least one configured service; bunching consumes every resulting snapshot.
OCC_INGESTION_MONITORED_SERVICES=190,972
```

At startup, ingestion imports the reference topology and creates missing enabled
`ingestion.poll_target` rows for the distinct stops on those services. The scheduler
then polls each enabled target once per minute. The bunching service consumes all
new normalized snapshots from that target set through ingestion's internal API; it
does not maintain a separate stop or service allow-list.

To broaden coverage, add service IDs to the comma-separated variable and restart
ingestion. Target seeding is additive: it creates missing targets but deliberately
does not disable or remove targets previously created for a service that is later
removed from the setting. Disable or remove those `ingestion.poll_target` rows
through an explicit operational change when reducing coverage.

With the service running, browse the interactive API at `http://localhost:<OCC_INGESTION_PORT>/swagger-ui.html` (default host port `6767`; the container listens on `8001`). These read-only endpoints return paginated envelopes except feed status and reference topology:

| Endpoint | Purpose |
| --- | --- |
| `GET /api/v1/arrivals?stopCode=&serviceId=&page=&size=` | Latest complete per-stop arrival snapshot |
| `GET /api/v1/map/observed-buses?serviceId=&page=&size=` | Latest source-monitored arrival positions with usable coordinates, freshness, and unverified identity labels for a map |
| `GET /api/v1/arrivals/history?stopCode=&serviceId=&from=&to=&page=&size=` | Retained normalized observation history |
| `GET /api/v1/feed-status` | Poll freshness and failures by stop |
| `GET /api/v1/poll-runs?page=&size=` | Poll audit runs |
| `GET /api/v1/reference-topology` | Active static reference-data version |
| `GET /api/v1/bus-routes?page=&size=` | Bus services and operators in the active reference topology |
| `GET /api/v1/bus-routes/{serviceId}` | Ordered bus stops for every direction of a bus service |
| `GET /api/v1/bus-stops?page=&size=` | Bus stops in the active reference topology |
| `GET /api/v1/bus-stops/{stopCode}` | One bus stop by stop code |

The machine-readable OpenAPI document is at `/v3/api-docs`.

Map items are individual DataMall arrival observations, not identified vehicles.
The endpoint omits unmonitored predictions, zero or invalid coordinates, and
future-dated fetches. It reports stale positions explicitly; a client must not
display stale positions as current buses. It does not deduplicate observations
across stops or assign plates, tracks, or drivers.

To export the runtime-generated contract to a file, start ingestion and run this
from `services/ingestion` in another terminal:

```powershell
.\mvnw.cmd -Popenapi org.springdoc:springdoc-openapi-maven-plugin:1.5:generate
```

```bash
./mvnw -Popenapi org.springdoc:springdoc-openapi-maven-plugin:1.5:generate
```

This writes `openapi/polling-ingestion.json` beside the service `pom.xml`. The export
uses the same running application endpoint as Swagger UI, so it includes the
actual controllers, DTOs, annotations and active Spring configuration.

A missing or invalid DataMall key can produce request errors even if the application starts. Stop the app with Ctrl+C.

Tests use fake DataMall responses and synthetic configuration, never the root `.env`. They do not prove live DataMall availability.
