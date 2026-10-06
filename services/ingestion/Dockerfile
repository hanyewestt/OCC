FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:25-jre-noble
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/target/ingestion-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8001
USER 10001:10001
ENTRYPOINT ["java", "-jar", "app.jar"]
