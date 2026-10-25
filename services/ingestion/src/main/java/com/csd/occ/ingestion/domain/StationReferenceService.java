package com.csd.occ.ingestion.domain;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StationReferenceService {
    private final StationReferenceRepository references;
    public StationReferenceService(StationReferenceRepository references) { this.references = references; }
    @Transactional(readOnly = true)
    public java.util.Optional<StationReference> find(String code) { return references.findById(code); }
    @Transactional
    public void importVersion(List<StationReference> stations) {
        if (stations.stream().map(StationReference::code).distinct().count() != stations.size())
            throw new IllegalArgumentException("Duplicate station codes in reference import");
        references.saveAll(stations);
    }
}
