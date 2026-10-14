ARG BUILD_IMAGE=eclipse-temurin:25.0.4_7-jdk-noble@sha256:6ee50f6bf158f3f5bb43b8b52985448743b5d3bfbcb0e99f3780c83d58ffa287
ARG RUNTIME_IMAGE=eclipse-temurin:25.0.4_7-jre-noble@sha256:ac58cdddfb2104865a2841af40dbf6e16f7473024519158aa0392cad33f9e00b
FROM ${BUILD_IMAGE} AS build

ARG OCC_SERVICE
ARG OCC_SERVICE_INTERNAL_PORT

WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw mvnw.cmd pom.xml ./
COPY services/ services/

RUN apt-get update \
    && apt-get install --no-install-recommends -y unzip \
    && rm -rf /var/lib/apt/lists/*
RUN case "${OCC_SERVICE}" in ingestion|disruption|planning|gateway) ;; \
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
