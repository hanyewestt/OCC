package com.csd.occ.ingestion.domain;

import java.time.Instant;

/** Fictional vehicle identity and predicted arrival from one simulation observation. */
public record DemoSimulationArrivalDetails(String registrationNumber, String operator, String vehicleType,
                                           String captainCode, Instant estimatedArrivalAt) {
    public DemoVehicleDetails vehicle() {
        return new DemoVehicleDetails(registrationNumber, operator, vehicleType, captainCode);
    }
}
