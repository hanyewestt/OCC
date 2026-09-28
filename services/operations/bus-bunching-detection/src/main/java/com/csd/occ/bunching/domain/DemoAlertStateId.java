package com.csd.occ.bunching.domain;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;
@Embeddable public record DemoAlertStateId(UUID demoRunId, String serviceId, String stopCode, int directionId) implements Serializable {}
