FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/target/bus-bunching-detection-0.0.1-SNAPSHOT.jar app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "app.jar"]
