package com.csd.occ.disruption.bunching.api;

import com.csd.occ.disruption.bunching.domain.ConsumerSelectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/internal/v1/disruption/consumer-selection", consumes = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Disruption consumer")
public class ConsumerSelectionController {
    private final ConsumerSelectionService selections;

    public ConsumerSelectionController(ConsumerSelectionService selections) { this.selections = selections; }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "selectBunchingConsumer", summary = "Select exactly one live or simulation feed")
    @ApiResponses({@ApiResponse(responseCode = "204", description = "Consumer feed selection changed"),
            @ApiResponse(responseCode = "400", description = "The mode and run ID combination is invalid")})
    public void select(@Valid @RequestBody SelectConsumerRequest request) {
        selections.select(request.mode(), request.demoRunId());
    }

    public record SelectConsumerRequest(@NotBlank @Pattern(regexp = "LIVE|DEMO") String mode, UUID demoRunId) {
        @AssertTrue(message = "DEMO requires demoRunId; LIVE must not include it")
        @JsonIgnore
        public boolean isScopeConsistent() {
            if ("LIVE".equals(mode)) return demoRunId == null;
            if ("DEMO".equals(mode)) return demoRunId != null;
            return true;
        }
    }
}
