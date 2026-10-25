package com.csd.occ.ingestion.infrastructure.config;

import com.csd.occ.ingestion.domain.HazardKind;
import com.csd.occ.ingestion.domain.HazardPollingService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HazardSchedule {
    private final HazardPollingService polling;
    private final HazardFeedProperties settings;
    public HazardSchedule(HazardPollingService polling, HazardFeedProperties settings) { this.polling = polling; this.settings = settings; }
    @Scheduled(fixedDelayString = "${occ.ingestion.hazards.flood.interval}", initialDelayString = "${occ.ingestion.hazards.flood.initial-delay}")
    public void flood() { if (settings.flood().enabled()) polling.poll(HazardKind.FLOOD, settings.flood().staleAfter()); }
    @Scheduled(fixedDelayString = "${occ.ingestion.hazards.rail.interval}", initialDelayString = "${occ.ingestion.hazards.rail.initial-delay}")
    public void rail() { if (settings.rail().enabled()) polling.poll(HazardKind.RAIL_DISRUPTION, settings.rail().staleAfter()); }
    @Scheduled(fixedDelayString = "${occ.ingestion.hazards.roadworks.interval}", initialDelayString = "${occ.ingestion.hazards.roadworks.initial-delay}")
    public void roadworks() { if (settings.roadworks().enabled()) polling.poll(HazardKind.ROADWORKS, settings.roadworks().staleAfter()); }
}
