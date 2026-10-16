ARG FLYWAY_IMAGE=flyway/flyway:13.10.0@sha256:f5dab54b2b51e5aa131ebc2be36fd8a7c18006541db06638469ec26b6ad2bfe2
FROM ${FLYWAY_IMAGE}

ARG OCC_SERVICE
ARG OCC_MIGRATION_SOURCE

RUN case "${OCC_SERVICE}" in ingestion|disruption|planning) ;; \
      *) echo "OCC_SERVICE must name an owned Phase 1 schema" >&2; exit 1 ;; \
    esac \
    && test -n "${OCC_MIGRATION_SOURCE}"

# This stack migrates PostgreSQL only; omit unused vendor drivers and their
# vulnerable transitive dependencies from the one-shot migration image.
RUN rm -f /flyway/drivers/databricks-jdbc-*.jar /flyway/drivers/mssql-jdbc-*.jar \
    && rm -rf /flyway/drivers/couchbase

COPY ${OCC_MIGRATION_SOURCE}/ /migrations/
COPY infra/flyway/${OCC_SERVICE}/ /callbacks/

USER 10001:10001
