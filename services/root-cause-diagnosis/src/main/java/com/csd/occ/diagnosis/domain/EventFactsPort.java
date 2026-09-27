package com.csd.occ.diagnosis.domain;

import java.util.UUID;

public interface EventFactsPort {
    BunchingEventFacts findConfirmedEvent(UUID eventId);
}
