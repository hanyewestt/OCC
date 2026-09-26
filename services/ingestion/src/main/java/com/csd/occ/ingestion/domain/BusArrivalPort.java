package com.csd.occ.ingestion.domain;

public interface BusArrivalPort {
    BusArrivalSnapshot arrivalsAt(String stopCode, String serviceId);
}
