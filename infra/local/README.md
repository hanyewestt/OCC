# Local database — Week 1

One PostgreSQL 18 + PostGIS 3.6 container, three schemas, three application
users. Tables are created by each service's own Flyway migrations, not here.

Source documents: `docs/plans/week-01/README.md` (Database ownership),
`01-platform-auth.md` and `03-bus-data-polling.md` (DDL), `CONVENTIONS.md` §9–10.

## Run (from repo root)

```bash
cp infra/local/.env.example infra/local/.env     # edit the passwords
docker compose -f infra/local/compose.yaml up -d
docker compose -f infra/local/compose.yaml ps    # wait for "healthy"
```

The init script runs only on the **first** start of an empty volume. After
editing anything under `infra/local/postgres/init/`:

```bash
docker compose -f infra/local/compose.yaml down -v   # -v drops the volume
docker compose -f infra/local/compose.yaml up -d
```

## Verify

```bash
# schemas exist and are owned by the right users
docker exec -it occ-postgres psql -U occ_admin -d occ -c '\dn'

# PostGIS is present
docker exec -it occ-postgres psql -U occ_admin -d occ -c 'select postgis_version();'

# isolation: this MUST fail with "permission denied for schema operations"
docker exec -it occ-postgres psql -U occ_planning -d occ -c 'select * from operations.user_profile;'
```

Tables appear once each Spring Boot service starts and runs Flyway. Until the
services exist (Plan 1 Day 1), you can apply the migrations by hand to check them:

```bash
for f in services/ingestion/src/main/resources/db/migration/V*.sql; do
  docker exec -i occ-postgres psql -v ON_ERROR_STOP=1 -U occ_ingestion -d occ < "$f"
done
```

(Same pattern for `operations` / `occ_operations` and `planning` / `occ_planning`.)

## What each service needs in `application.yml`

The optional bunching service uses its own `bunching` schema and `occ_bunching`
role. Set `OCC_BUNCHING_DB_PASSWORD` in `.env`; `02_bunching_schema.sh` provisions
them on a fresh volume. For an existing volume, recreate the container with
`docker compose -f infra/local/compose.yaml up -d postgres` to load the new
environment, then run `docker compose -f infra/local/compose.yaml exec -T postgres bash /docker-entrypoint-initdb.d/02_bunching_schema.sh`.
This script is idempotent and preserves existing data and role passwords; no
volume reset is necessary. After applying ingestion migration V005, run
`docker compose -f infra/local/compose.yaml exec -T postgres bash /docker-entrypoint-initdb.d/03_bunching_read_grants.sh`.
This separately grants read-only access to ingestion's two bunching contract views,
not its underlying tables. No ingestion write permissions are granted.
See `services/bus-bunching-detection/README.md` for service configuration.

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/occ
    username: occ_ingestion          # occ_operations / occ_planning
    password: ${OCC_INGESTION_DB_PASSWORD}
  flyway:
    default-schema: ingestion        # operations / planning
    locations: classpath:db/migration
  jpa:
    hibernate:
      ddl-auto: none                 # mandatory; JPA never mutates schema
```

## Deviations from the plan documents

| What | Why |
|---|---|
| Constraints are named (`pk_`, `uq_`, `fk_`, `ck_`, `ix_`) | CONVENTIONS.md §9 requires it; the DDL in the plans left them unnamed. Column definitions are otherwise verbatim. |
| Ingestion DDL split into four migrations, Operations into two | CONVENTIONS.md §9 example is one migration per table/feature (`V001__create_user_profile.sql`). |
| Image is `postgis/postgis:18-3.6`, not 16 | `project-v2.md` targets PostgreSQL 18.6 on RDS. Local should match the major version. Note PG18 images mount data at `/var/lib/postgresql`, not `/var/lib/postgresql/data`. |
| Roles/schemas created by a shell init script, not SQL | Passwords come from the environment; a `.sql` file in `docker-entrypoint-initdb.d` cannot read env vars. |
| `ingestion.bus_route_stop` keeps the plan's extra `unique (…, stop_code, stop_sequence)` | Kept verbatim. It is redundant with the primary key (a superset of PK columns is always unique). Harmless; flag to Plan 3 owners. |

## Not done here (belongs to other tasks)

- Caddy, Operations, Ingestion, Planning services in Compose — Plan 1 Day 1/4.
- Gradle projects that actually run these migrations — Plan 1 Day 1.
- Seed data for stops 190/972 — Plan 3 Day 2 (`poll_target` rows).
- 24-hour retention job for observations — `project-v2.md` §5, not Week 1.
- ADR for schema ownership — Plan 1 Day 1.
- `.gitignore` currently does not ignore `.env` — Plan 1 Day 1 "expanded .gitignore". Add `infra/local/.env` before committing anything.
