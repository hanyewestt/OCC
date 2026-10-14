package com.csd.occ.planning.diagnosis.domain;

public interface RecommendationPayloadCodecPort {
    String encode(java.util.UUID requestId, BunchingEventFacts event, RootCauseDiagnosis diagnosis, String classification);
}
