package com.csd.occ.planning.recommendationengine.domain;

import java.util.Optional;

public interface BunchingMitigationContextPort {

    Optional<BunchingMitigationInputs> findFor(ConfirmedBunchingEvent event);
}
