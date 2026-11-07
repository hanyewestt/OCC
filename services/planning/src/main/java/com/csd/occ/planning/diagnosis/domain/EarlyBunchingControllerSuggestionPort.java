package com.csd.occ.planning.diagnosis.domain;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface EarlyBunchingControllerSuggestionPort {
    Optional<EarlyBunchingControllerSuggestion> findByRequestId(UUID requestId);
    Optional<EarlyBunchingControllerSuggestion> recordIfAbsent(EarlyBunchingControllerSuggestion suggestion);
    List<EarlyBunchingControllerSuggestion> findAll(String classification, UUID demoRunId, int offset, int limit);
    long count(String classification, UUID demoRunId);
}
