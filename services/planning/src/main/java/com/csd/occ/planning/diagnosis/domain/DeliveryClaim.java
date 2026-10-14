package com.csd.occ.planning.diagnosis.domain;

import java.util.UUID;

public record DeliveryClaim(UUID deliveryId, String payloadJson, int attemptNumber) { }
