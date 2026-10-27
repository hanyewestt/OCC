package com.csd.occ.ingestion.domain;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "demo_vehicle_assignment", schema = "ingestion")
public class DemoVehicleAssignment {
    protected DemoVehicleAssignment() {}
    @EmbeddedId private DemoVehicleAssignmentId id;
    private String serviceId;
    private short directionId;
    private String operator;
    private String vehicleRegistrationNumber;
    private String vehicleType;
    private String captainCode;
    private String busId;
    // Null means this synthetic bus has no authenticated captain assignment.
    private java.util.UUID captainUserId;
    public DemoVehicleAssignment(DemoVehicleAssignmentId id, String registration, String operator,
                                 String vehicleType, String captain) {
        this(id, "190", 1, registration, operator, vehicleType, captain);
    }
    public DemoVehicleAssignment(DemoVehicleAssignmentId id, String serviceId, int directionId,
                                 String registration, String operator, String vehicleType, String captain) {
        this.id = id; this.serviceId = serviceId; this.directionId = (short) directionId; this.operator = operator;
        vehicleRegistrationNumber = registration; this.vehicleType = vehicleType; captainCode = captain;
        busId = java.util.Objects.requireNonNull(id).tripId();
        if (busId.isBlank()) throw new IllegalArgumentException("busId is required");
    }
    public DemoVehicleAssignment(DemoVehicleAssignmentId id, String serviceId, int directionId,
            String registration, String operator, String vehicleType, String captain, java.util.UUID captainUserId) {
        this(id, serviceId, directionId, registration, operator, vehicleType, captain);
        this.captainUserId = captainUserId;
    }
    public DemoVehicleAssignmentId id() { return id; }
    public String busId() { return busId; }
    public java.util.Optional<java.util.UUID> captainUserId() { return java.util.Optional.ofNullable(captainUserId); }
    public String serviceId() { return serviceId; }
    public int directionId() { return directionId; }
    public String operator() { return operator; }
    public String vehicleRegistrationNumber() { return vehicleRegistrationNumber; }
    public String vehicleType() { return vehicleType; }
    public String captainCode() { return captainCode; }
}
