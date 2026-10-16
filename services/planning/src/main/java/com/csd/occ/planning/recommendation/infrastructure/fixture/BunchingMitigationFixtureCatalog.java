package com.csd.occ.planning.recommendation.infrastructure.fixture;

import com.csd.occ.planning.recommendation.domain.BunchingMitigationInputs;
import com.csd.occ.planning.recommendation.domain.SimulatedImpactEstimate;
import com.csd.occ.planning.recommendation.domain.ValidatedMitigationContext;
import java.util.List;

record BunchingMitigationFixtureCatalog(List<Entry> entries) {

    BunchingMitigationFixtureCatalog {
        entries = List.copyOf(entries);
    }

    record Entry(
            String serviceId,
            int directionId,
            String monitoredStopCode,
            ValidatedMitigationContext context,
            SimulatedImpactEstimate impact) {

        FixtureKey key() {
            return new FixtureKey(serviceId, directionId, monitoredStopCode);
        }

        BunchingMitigationInputs inputs() {
            return new BunchingMitigationInputs(context, impact);
        }
    }

    record FixtureKey(String serviceId, int directionId, String monitoredStopCode) {
    }
}
