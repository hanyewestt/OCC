package com.csd.occ.operations.infrastructure.config;

import com.csd.occ.operations.domain.TrackInferenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "occ.track-inference.enabled", havingValue = "true")
public class TrackInferenceSchedule {
    private static final Logger log = LoggerFactory.getLogger(TrackInferenceSchedule.class);
    private final TrackInferenceService inference;
    public TrackInferenceSchedule(TrackInferenceService inference) { this.inference = inference; }
    @Scheduled(fixedDelayString = "${occ.track-inference.interval}", initialDelayString = "${occ.track-inference.initial-delay}")
    public void refresh() {
        try { inference.refresh(); }
        catch (RuntimeException failure) { log.warn("Track projection refresh failed; stored positions age out normally", failure); }
    }
}
