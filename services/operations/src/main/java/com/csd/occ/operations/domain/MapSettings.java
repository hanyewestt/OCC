package com.csd.occ.operations.domain;

public record MapSettings(String tileUrl, String attributionText, String attributionUrl, long refreshIntervalMs,
        boolean cognitoEnabled, boolean rawObservationsDefault) { }
