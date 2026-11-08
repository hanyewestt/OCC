package com.csd.occ.planning.diagnosis.api.dto;

import com.csd.occ.planning.recommendation.api.dto.ControllerBusInstructionRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record EarlyBunchingControllerSuggestionRequest(
        @NotEmpty @Size(max = 3) List<@Valid ControllerBusInstructionRequest> instructions,
        @Size(max = 1000) String controllerRationale) { }
