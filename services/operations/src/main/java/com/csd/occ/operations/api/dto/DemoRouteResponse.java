package com.csd.occ.operations.api.dto;

import java.util.List;
import java.util.UUID;

public record DemoRouteResponse(UUID runId, String serviceId, int directionId,
        String routeKind, String classification, List<DemoRouteSequenceResponse> sequences) { }
