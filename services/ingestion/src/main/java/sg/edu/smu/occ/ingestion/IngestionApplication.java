package sg.edu.smu.occ.ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// Keep the legacy entry point/entities for compatibility; new polling code follows docs/CONVENTIONS.md.
@SpringBootApplication(scanBasePackages = {"sg.edu.smu.occ.ingestion", "com.csd.occ.ingestion"})
@ConfigurationPropertiesScan("com.csd.occ.ingestion.infrastructure.config")
@EnableScheduling
public class IngestionApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestionApplication.class, args);
    }
}
