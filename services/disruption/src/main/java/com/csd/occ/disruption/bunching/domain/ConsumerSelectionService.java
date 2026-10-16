package com.csd.occ.disruption.bunching.domain;
import java.time.Clock;
import java.util.UUID;
import com.csd.occ.disruption.bunching.domain.IngestionFeedHeadPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class ConsumerSelectionService {
    private final ConsumerStateRepository states;
    private final IngestionFeedHeadPort liveHead;
    private final Clock clock;
    public ConsumerSelectionService(ConsumerStateRepository states,
                                    IngestionFeedHeadPort liveHead, Clock clock) {
        this.states = states; this.liveHead = liveHead; this.clock = clock;
    }
    @Transactional(readOnly = true) public ConsumerState current() { return states.findById(1).orElseThrow(); }
    @Transactional public void select(String mode, UUID runId) {
        if (!("LIVE".equals(mode) || "DEMO".equals(mode))) throw new InvalidConsumerSelectionException("mode must be LIVE or DEMO");
        if ("DEMO".equals(mode) && runId == null) throw new InvalidConsumerSelectionException("demoRunId is required for DEMO");
        if ("LIVE".equals(mode) && runId != null) throw new InvalidConsumerSelectionException("LIVE cannot include demoRunId");
        long headSequence = "LIVE".equals(mode) ? liveHead.currentLiveSequence() : 0;
        ConsumerState state = states.findWithLockById(1).orElseThrow();
        if ("LIVE".equals(mode)) state.selectLive(headSequence, clock.instant());
        else state.selectDemo(runId, clock.instant());
    }
}
