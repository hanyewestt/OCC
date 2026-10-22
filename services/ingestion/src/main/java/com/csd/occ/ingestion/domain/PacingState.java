package com.csd.occ.ingestion.domain;

/** How one headway compares with the target headway (project-v2 section 6). */
public enum PacingState {
    TOO_CLOSE,
    ON_TARGET,
    TOO_FAR
}
