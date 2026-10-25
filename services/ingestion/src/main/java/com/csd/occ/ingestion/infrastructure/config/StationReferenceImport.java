package com.csd.occ.ingestion.infrastructure.config;

import com.csd.occ.ingestion.domain.HazardEvidencePort;
import com.csd.occ.ingestion.domain.StationReference;
import com.csd.occ.ingestion.domain.StationReferenceService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class StationReferenceImport implements CommandLineRunner {
    private final StationReferenceProperties settings;
    private final ResourceLoader resources;
    private final StationReferenceService stations;
    private final JsonMapper json;
    private final HazardEvidencePort evidence;
    public StationReferenceImport(StationReferenceProperties settings, ResourceLoader resources,
            StationReferenceService stations, JsonMapper json, HazardEvidencePort evidence) {
        this.settings = settings; this.resources = resources; this.stations = stations; this.json = json; this.evidence = evidence;
    }
    @Override public void run(String... args) throws Exception {
        if (settings.location().isBlank()) return;
        byte[] bytes;
        try (var input = resources.getResource(settings.location()).getInputStream()) {
            bytes = input.readNBytes(settings.maxBytes() + 1);
        }
        if (bytes.length > settings.maxBytes()) throw new IllegalArgumentException("Station reference exceeds byte limit");
        String payload = new String(bytes, StandardCharsets.UTF_8);
        var document = json.readValue(payload, Document.class);
        if (document.stations() == null || document.stations().isEmpty()) throw new IllegalArgumentException("Station reference is empty");
        String checksum = evidence.checksum(payload);
        stations.importVersion(document.stations().stream().map(station -> new StationReference(station.code(), station.name(),
                station.latitude(), station.longitude(), checksum)).toList());
    }
    record Document(List<Station> stations) {}
    record Station(String code, String name, BigDecimal latitude, BigDecimal longitude) {}
}
