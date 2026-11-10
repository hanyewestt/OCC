package com.csd.occ.ingestion.domain;

/** Public bootstrap values; never include service credentials or signing keys. */
public record FrontendConfig(String projectUrl, String publishableKey, long controllerRefreshIntervalSeconds,
                             long ingestionPollIntervalSeconds, long routeIncidentGapToleranceSeconds,
                             long captainRefreshIntervalSeconds, long publicNoticeRefreshIntervalSeconds) { }
