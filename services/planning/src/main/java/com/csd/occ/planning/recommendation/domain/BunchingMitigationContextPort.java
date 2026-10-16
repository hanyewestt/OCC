package com.csd.occ.planning.recommendation.domain;

import java.util.Optional;

public interface BunchingMitigationContextPort {

    Optional<BunchingMitigationInputs> findFor(ConfirmedBunchingEvent event);
}
