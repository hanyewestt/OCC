package com.csd.occ.planning.diagnosis.api.dto;

import java.util.List;

public record EarlyBunchingControllerSuggestionPageResponse(List<EarlyBunchingControllerSuggestionResponse> items,
        int page, int size, long totalItems, int totalPages) { }
