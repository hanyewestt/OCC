package com.csd.occ.planning.advisory.api.dto;

import com.csd.occ.planning.advisory.domain.AdvisoryCandidate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record DecideAdvisoryRequest(@Valid AdvisoryCandidate override, @Size(max = 2000) String rationale,
                                    UUID replaceInstructionId) {}
