package com.csd.occ.planning.advisory.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record DecideAdvisoryGroupRequest(@NotEmpty List<@NotNull UUID> proposalIds) {}
