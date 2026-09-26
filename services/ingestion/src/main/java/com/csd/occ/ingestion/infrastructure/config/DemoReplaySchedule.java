package com.csd.occ.ingestion.infrastructure.config;
import com.csd.occ.ingestion.domain.DemoReplayService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(name = "occ.ingestion.demo.enabled", havingValue = "true")
public class DemoReplaySchedule {
    private final DemoReplayService replay;
    public DemoReplaySchedule(DemoReplayService replay) { this.replay = replay; }
    @Scheduled(fixedDelayString = "${occ.ingestion.demo.step-interval}")
    public void replay() { replay.replayNext(); }
}
