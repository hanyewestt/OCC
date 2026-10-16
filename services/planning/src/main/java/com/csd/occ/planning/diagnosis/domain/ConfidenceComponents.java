package com.csd.occ.planning.diagnosis.domain;

public record ConfidenceComponents(double provenanceGate, double temporalAlignment, double spatialAlignment,
                                   double incidentSpecificity, double headwayChronology) {
    public double score() {
        return Math.min(0.85, provenanceGate * (0.30 * temporalAlignment + 0.25 * spatialAlignment
                + 0.20 * incidentSpecificity + 0.25 * headwayChronology));
    }
}
