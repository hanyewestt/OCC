FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /workspace
COPY pom.xml mvnw ./
COPY .mvn .mvn
COPY services/ingestion/pom.xml services/ingestion/pom.xml
COPY services/disruption/pom.xml services/disruption/pom.xml
COPY services/planning/pom.xml services/planning/pom.xml
COPY services/gateway/pom.xml services/gateway/pom.xml
COPY services/planning/src services/planning/src
RUN apt-get update && apt-get install --no-install-recommends -y unzip \
    && rm -rf /var/lib/apt/lists/*
RUN chmod +x mvnw && ./mvnw -B -ntp -pl services/planning -Dmaven.test.skip=true package

FROM eclipse-temurin:25-jre-noble
RUN apt-get update && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/services/planning/target/planning-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8003
USER 10001:10001
ENTRYPOINT ["java", "-jar", "app.jar"]
