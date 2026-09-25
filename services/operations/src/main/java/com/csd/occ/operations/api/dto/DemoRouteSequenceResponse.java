package com.csd.occ.operations.api.dto;

import java.util.List;

public record DemoRouteSequenceResponse(String operator, List<DemoRouteStopResponse> stops) { }
