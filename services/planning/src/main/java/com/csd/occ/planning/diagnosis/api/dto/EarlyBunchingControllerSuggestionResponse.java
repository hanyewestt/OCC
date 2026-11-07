package com.csd.occ.planning.diagnosis.api.dto;

import com.csd.occ.planning.diagnosis.domain.EarlyBunchingControllerSuggestion;
import com.csd.occ.planning.recommendation.api.dto.ControllerBusInstructionResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EarlyBunchingControllerSuggestionResponse(UUID requestId, List<ControllerBusInstructionResponse> instructions,
        String controllerRationale, Instant suggestedAt) {
    public static EarlyBunchingControllerSuggestionResponse from(EarlyBunchingControllerSuggestion suggestion) {
        return new EarlyBunchingControllerSuggestionResponse(suggestion.requestId(), suggestion.instructions().stream()
                .map(ControllerBusInstructionResponse::from).toList(), suggestion.controllerRationale(), suggestion.suggestedAt());
    }
}
