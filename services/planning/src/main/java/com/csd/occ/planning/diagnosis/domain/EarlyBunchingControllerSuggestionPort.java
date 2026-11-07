package com.csd.occ.planning.diagnosis.domain;

import java.util.Optional;
import java.util.UUID;

public interface EarlyBunchingControllerSuggestionPort {
    Optional<EarlyBunchingControllerSuggestion> findByRequestId(UUID requestId);
    Optional<EarlyBunchingControllerSuggestion> recordIfAbsent(EarlyBunchingControllerSuggestion suggestion);
}
