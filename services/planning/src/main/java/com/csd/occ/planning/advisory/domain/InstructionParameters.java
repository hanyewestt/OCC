package com.csd.occ.planning.advisory.domain;

/** Only parameters applicable to the selected instruction type are present. */
public record InstructionParameters(
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String stopCode,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String stopName,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) Integer stopSequence,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true, description = "Number of subsequent stops") Integer subsequentStopCount,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true, description = "Speed reduction in km/h") Integer speedReductionKmh,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true, description = "Duration in seconds") Integer durationSeconds) {}
