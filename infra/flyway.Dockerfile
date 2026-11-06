ARG FLYWAY_IMAGE=flyway/flyway:13.10.0@sha256:f5dab54b2b51e5aa131ebc2be36fd8a7c18006541db06638469ec26b6ad2bfe2
FROM ${FLYWAY_IMAGE}

ARG OCC_SERVICE
ARG OCC_MIGRATION_SOURCE
ARG GUAVA_VERSION=33.7.2-jre
ARG GUAVA_SHA256=b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c

RUN case "${OCC_SERVICE}" in ingestion|disruption|planning) ;; \
      *) echo "OCC_SERVICE must name an owned Phase 1 schema" >&2; exit 1 ;; \
    esac \
    && test -n "${OCC_MIGRATION_SOURCE}"

# This stack migrates PostgreSQL only; omit unused vendor drivers and their
# vulnerable transitive dependencies from the one-shot migration image. Flyway's
# GCP driver keeps Guava, so replace it with the CVE-fixed release and verify it.
RUN apt-get update \
    && apt-get upgrade -y \
    && apt-get install --no-install-recommends -y curl libbrotli1 \
    && curl --fail --silent --show-error --location --retry 5 --retry-delay 2 --retry-max-time 60 --retry-all-errors \
        "https://repo1.maven.org/maven2/com/google/guava/guava/${GUAVA_VERSION}/guava-${GUAVA_VERSION}.jar" \
        --output /tmp/guava.jar \
    && echo "${GUAVA_SHA256}  /tmp/guava.jar" | sha256sum --check \
    && rm -f /flyway/drivers/databricks-jdbc-*.jar /flyway/drivers/mssql-jdbc-*.jar \
        /flyway/drivers/gcp/guava-*.jar \
    && rm -rf /flyway/drivers/couchbase \
    && mv /tmp/guava.jar "/flyway/drivers/gcp/guava-${GUAVA_VERSION}.jar" \
    && apt-get purge --auto-remove -y curl \
    && rm -rf /var/lib/apt/lists/*

COPY ${OCC_MIGRATION_SOURCE}/ /migrations/
COPY infra/flyway/${OCC_SERVICE}/ /callbacks/

USER 10001:10001
