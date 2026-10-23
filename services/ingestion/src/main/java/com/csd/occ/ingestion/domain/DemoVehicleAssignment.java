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
    public DemoVehicleAssignment(DemoVehicleAssignmentId id, String registration, String operator,
                                 String vehicleType, String captain) {
        this(id, "190", 1, registration, operator, vehicleType, captain);
    }
    public DemoVehicleAssignment(DemoVehicleAssignmentId id, String serviceId, int directionId,
                                 String registration, String operator, String vehicleType, String captain) {
        this.id = id; this.serviceId = serviceId; this.directionId = (short) directionId; this.operator = operator;
        vehicleRegistrationNumber = registration; this.vehicleType = vehicleType; captainCode = captain;
    }
    public String serviceId() { return serviceId; }
    public int directionId() { return directionId; }
    public String operator() { return operator; }
    public String vehicleRegistrationNumber() { return vehicleRegistrationNumber; }
    public String vehicleType() { return vehicleType; }
    public String captainCode() { return captainCode; }
}
