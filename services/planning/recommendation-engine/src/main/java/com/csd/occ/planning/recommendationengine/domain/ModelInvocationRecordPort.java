package com.csd.occ.planning.recommendationengine.domain;

public interface ModelInvocationRecordPort {
    default void attach(java.util.UUID recommendationId) { }

    void save(ModelInvocationRecord record);
}
