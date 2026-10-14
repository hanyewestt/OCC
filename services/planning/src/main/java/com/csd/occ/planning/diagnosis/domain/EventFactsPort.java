package com.csd.occ.planning.diagnosis.domain;

import java.util.UUID;

public interface EventFactsPort {
    BunchingEventFacts findConfirmedEvent(UUID eventId);
}
