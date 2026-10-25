package com.csd.occ.planning.advisory.api.dto;

import java.util.UUID;

/** Optional body for atomically replacing an active movement instruction. */
public record ReplaceInstructionRequest(UUID replaceInstructionId) {}
