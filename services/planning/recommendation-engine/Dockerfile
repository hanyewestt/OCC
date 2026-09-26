FROM maven:3.9.11-eclipse-temurin-25 AS build

WORKDIR /workspace
COPY pom.xml .
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline

COPY src src
RUN mvn --batch-mode --no-transfer-progress package -DskipTests

FROM eclipse-temurin:25-jre

WORKDIR /app
COPY --from=build /workspace/target/recommendation-engine-0.0.1-SNAPSHOT.jar application.jar

USER 10001
EXPOSE 8002
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
