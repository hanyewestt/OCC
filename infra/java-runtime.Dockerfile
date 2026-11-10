# Runtime-only image for one verified service JAR. The CD pipeline stages a
# narrow context that contains exactly app.jar; the source-building
# infra/java.Dockerfile remains for local workflows.
ARG RUNTIME_IMAGE=eclipse-temurin:25.0.4.1_1-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab
FROM ${RUNTIME_IMAGE}

ARG OCC_SERVICE_INTERNAL_PORT

RUN sed -i 's#http://archive.ubuntu.com#https://archive.ubuntu.com#g; s#http://security.ubuntu.com#https://security.ubuntu.com#g' /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get update \
    && apt-get upgrade -y \
    && apt-get install --no-install-recommends -y curl libbrotli1 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY app.jar app.jar
EXPOSE ${OCC_SERVICE_INTERNAL_PORT}
USER 10001:10001
ENTRYPOINT ["java", "-jar", "app.jar"]
