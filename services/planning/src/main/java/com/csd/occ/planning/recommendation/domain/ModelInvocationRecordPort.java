package com.csd.occ.planning.recommendation.domain;

public interface ModelInvocationRecordPort {
    default void attach(java.util.UUID recommendationId) { }

    void save(ModelInvocationRecord record);
}
