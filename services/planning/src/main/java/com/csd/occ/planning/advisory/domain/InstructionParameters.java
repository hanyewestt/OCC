package com.csd.occ.planning.advisory.domain;

/** Only parameters applicable to the selected instruction type are present. */
public record InstructionParameters(String stopCode, String stopName, Integer stopSequence,
                                    Integer subsequentStopCount, Integer speedReductionKmh, Integer durationSeconds) {}
