package com.csd.occ.ingestion.domain;

import java.util.List;
import java.util.UUID;

public record DemoRunDetails(UUID id, String status, int currentStep, DemoRunConfiguration configuration,
                             List<DemoAlertDetails> alerts) {
    public DemoRunDetails { alerts = List.copyOf(alerts); }
    public DemoRunDetails(UUID id, String status, int currentStep, List<DemoAlertDetails> alerts) {
        this(id, status, currentStep, DemoRunConfiguration.defaults(), alerts);
    }
}
