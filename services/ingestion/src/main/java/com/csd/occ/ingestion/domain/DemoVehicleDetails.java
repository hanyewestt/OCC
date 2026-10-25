package com.csd.occ.ingestion.domain;

public record DemoVehicleDetails(String registrationNumber, String operator, String vehicleType, String captainCode,
                                 String busId) {
    public DemoVehicleDetails(String registrationNumber, String operator, String vehicleType, String captainCode) {
        this(registrationNumber, operator, vehicleType, captainCode, registrationNumber);
    }
}
