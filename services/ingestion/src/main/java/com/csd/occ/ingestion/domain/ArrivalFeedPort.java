package com.csd.occ.ingestion.domain;

public interface ArrivalFeedPort {
    ArrivalFetch fetch(ArrivalPollTarget target);
}
