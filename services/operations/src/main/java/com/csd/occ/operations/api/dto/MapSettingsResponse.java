package com.csd.occ.operations.api.dto;
public record MapSettingsResponse(String tileUrl, String attributionText, String attributionUrl, long refreshIntervalMs,
        boolean cognitoEnabled) { }
