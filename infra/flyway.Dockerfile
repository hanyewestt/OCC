ARG FLYWAY_IMAGE=flyway/flyway:13.10.0@sha256:f5dab54b2b51e5aa131ebc2be36fd8a7c18006541db06638469ec26b6ad2bfe2
FROM ${FLYWAY_IMAGE}

ARG GUAVA_VERSION=33.7.2-jre
ARG GUAVA_SHA256=b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c

# This stack migrates PostgreSQL only; omit unused vendor drivers and their
# vulnerable transitive dependencies from the one-shot migration image. Flyway's
# GCP driver keeps Guava, so replace it with the CVE-fixed release and verify it.
RUN sed -i 's#http://archive.ubuntu.com#https://archive.ubuntu.com#g; s#http://security.ubuntu.com#https://security.ubuntu.com#g' /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get update \
    && apt-get upgrade -y \
    && apt-get install --no-install-recommends -y curl libbrotli1 postgresql-client \
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

COPY services/ingestion/src/main/resources/db/migration/ /migrations/ingestion/
COPY services/disruption/src/main/resources/db/migration/ /migrations/disruption/
COPY services/planning/src/main/resources/db/migration/ /migrations/planning/
COPY infra/flyway/ /callbacks/
COPY infra/postgres/bootstrap.sql /bootstrap/bootstrap.sql
COPY infra/db-setup.sh /setup/db-setup.sh
ENTRYPOINT ["/bin/sh", "/setup/db-setup.sh"]

USER 10001:10001
