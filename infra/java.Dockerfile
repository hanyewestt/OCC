ARG BUILD_IMAGE=eclipse-temurin:25.0.4.1_1-jdk-noble@sha256:589ff4cc3f71aab462e7048a47a0d10edf57fbccde3fceea2281e610bf5880b4
ARG RUNTIME_IMAGE=eclipse-temurin:25.0.4.1_1-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab
FROM ${BUILD_IMAGE} AS build

ARG OCC_SERVICE
ARG OCC_SERVICE_INTERNAL_PORT

WORKDIR /workspace
RUN apt-get update \
    && apt-get install --no-install-recommends -y unzip \
    && rm -rf /var/lib/apt/lists/*
COPY .mvn/ .mvn/
COPY mvnw mvnw.cmd pom.xml ./
COPY services/ services/

RUN --mount=type=cache,id=occ-maven-${OCC_SERVICE},target=/root/.m2 \
    case "${OCC_SERVICE}" in ingestion|disruption|planning|gateway) ;; \
      *) echo "OCC_SERVICE must name one of the four phase-one applications" >&2; exit 1 ;; \
    esac \
    && test -n "${OCC_SERVICE_INTERNAL_PORT}" \
    && chmod +x mvnw \
    && ./mvnw -B -ntp -pl "services/${OCC_SERVICE}" -Dmaven.test.skip=true package

FROM ${RUNTIME_IMAGE} AS runtime

ARG OCC_SERVICE
ARG OCC_SERVICE_INTERNAL_PORT

RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build "/workspace/services/${OCC_SERVICE}/target/${OCC_SERVICE}-0.0.1-SNAPSHOT.jar" app.jar
EXPOSE ${OCC_SERVICE_INTERNAL_PORT}
USER 10001:10001
ENTRYPOINT ["java", "-jar", "app.jar"]
