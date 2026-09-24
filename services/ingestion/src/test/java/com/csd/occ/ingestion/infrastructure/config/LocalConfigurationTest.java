package com.csd.occ.ingestion.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class LocalConfigurationTest {
    @TempDir Path temporaryRepository;

    @Test
    void localProfileLoadsRootEnvironmentFromServiceWorkingDirectory() throws Exception {
        runProbe("local");
    }

    @Test
    void deploymentDoesNotLoadLocalEnvironment() throws Exception {
        runProbe("deployment");
    }

    @Test
    void localProfileHonorsExistingDatabaseUrlOverride() throws Exception {
        runProbe("override");
    }

    private void runProbe(String profile) throws Exception {
        Path service = Files.createDirectories(temporaryRepository.resolve("services/ingestion"));
        Files.writeString(temporaryRepository.resolve(".env"),
                "OCC_INGESTION_DB_PASSWORD=synthetic-password\nDATAMALL_API_KEY=synthetic-key\n"
                + "POSTGRES_DB=synthetic_db\nPOSTGRES_PORT=5544\n"
                + (profile.equals("override") ? "SPRING_DATASOURCE_URL=jdbc:postgresql://example.test/override\n" : ""));
        String separator = System.getProperty("path.separator");
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(separator))
                .map(entry -> Path.of(entry).toAbsolutePath().toString()).collect(Collectors.joining(separator));
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, ConfigurationProbe.class.getName(), profile).directory(service.toFile());
        builder.environment().keySet().removeIf(key -> key.startsWith("SPRING_") || key.startsWith("OCC_")
                || key.startsWith("POSTGRES_") || key.equals("DATAMALL_API_KEY"));
        Path output = temporaryRepository.resolve("probe.log");
        Process process = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertThat(finished).isTrue();
        assertThat(process.exitValue()).withFailMessage(Files.readString(output)).isZero();
    }

    public static class ConfigurationProbe {
        public static void main(String[] args) {
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("probe", Map.of(
                    "spring.profiles.active", args[0].equals("override") ? "local" : args[0])));
            ConfigDataEnvironmentPostProcessor.applyTo(environment);
            if (!args[0].equals("deployment")) {
                String expectedUrl = args[0].equals("override") ? "jdbc:postgresql://example.test/override"
                        : "jdbc:postgresql://localhost:5544/synthetic_db";
                if (!"synthetic-password".equals(environment.getProperty("spring.datasource.password"))
                        || !"synthetic-key".equals(environment.getProperty("occ.datamall.account-key"))
                        || !expectedUrl.equals(environment.getProperty("spring.datasource.url"))) {
                    throw new AssertionError("Root local configuration was not loaded correctly");
                }
            } else if (environment.containsProperty("DATAMALL_API_KEY")) {
                throw new AssertionError("Deployment loaded local secrets");
            }
        }
    }
}
