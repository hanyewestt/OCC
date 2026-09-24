FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/ingestion-0.0.1-SNAPSHOT.jar app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "app.jar"]
