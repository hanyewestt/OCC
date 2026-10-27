package com.csd.occ.ingestion.domain;

import java.time.Instant;

/** Fictional vehicle identity and predicted arrival from one simulation observation. */
public record DemoSimulationArrivalDetails(String registrationNumber, String operator, String vehicleType,
                                           String captainCode, Instant estimatedArrivalAt, String busId) {
    public DemoSimulationArrivalDetails(String registrationNumber, String operator, String vehicleType,
                                        String captainCode, Instant estimatedArrivalAt) {
        this(registrationNumber, operator, vehicleType, captainCode, estimatedArrivalAt, registrationNumber);
    }
    public DemoVehicleDetails vehicle() {
        return new DemoVehicleDetails(registrationNumber, operator, vehicleType, captainCode, busId);
    }
}
