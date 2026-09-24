package com.csd.occ.ingestion.domain;

public record ArrivalPollTarget(String source, String scopeKey, String stopCode) {
    public ArrivalPollTarget {
        if (!"DATAMALL".equals(source) || stopCode == null || !stopCode.matches("[0-9]{5}")
                || !scopeKey.equals("stop:" + stopCode)) {
            throw new IllegalArgumentException("Invalid Bus Arrival target");
        }
    }
}
