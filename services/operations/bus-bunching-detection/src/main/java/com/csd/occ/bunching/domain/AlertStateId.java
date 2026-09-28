package com.csd.occ.bunching.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;

@Embeddable
public record AlertStateId(String serviceId, String stopCode, int directionId) implements Serializable {
}
