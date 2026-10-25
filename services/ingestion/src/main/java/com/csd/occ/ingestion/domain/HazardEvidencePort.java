package com.csd.occ.ingestion.domain;

import java.util.List;

public interface HazardEvidencePort {
    String encodePages(List<String> pages);
    String encodeFact(HazardFeedPort.Fact fact);
    String checksum(String text);
}
