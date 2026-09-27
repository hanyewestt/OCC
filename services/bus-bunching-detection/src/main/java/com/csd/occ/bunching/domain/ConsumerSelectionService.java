package com.csd.occ.bunching.domain;
import java.time.Clock;
import java.util.UUID;
import com.csd.occ.bunching.domain.IngestionFeedHeadPort;
import com.csd.occ.bunching.domain.IngestionFeedCursor;
import com.csd.occ.bunching.domain.IngestionFeedCursorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class ConsumerSelectionService {
    private final ConsumerSelectionRepository selections;
    private final IngestionFeedCursorRepository cursors;
    private final IngestionFeedHeadPort liveHead;
    private final Clock clock;
    public ConsumerSelectionService(ConsumerSelectionRepository selections, IngestionFeedCursorRepository cursors,
                                    IngestionFeedHeadPort liveHead, Clock clock) {
        this.selections = selections; this.cursors = cursors; this.liveHead = liveHead; this.clock = clock;
    }
    @Transactional(readOnly = true) public ConsumerSelection current() { return selections.findById(1).orElseThrow(); }
    @Transactional public void select(String mode, UUID runId) {
        if (!("LIVE".equals(mode) || "DEMO".equals(mode))) throw new InvalidConsumerSelectionException("mode must be LIVE or DEMO");
        if ("DEMO".equals(mode) && runId == null) throw new InvalidConsumerSelectionException("demoRunId is required for DEMO");
        if ("LIVE".equals(mode) && runId != null) throw new InvalidConsumerSelectionException("LIVE cannot include demoRunId");
        long headSequence = "LIVE".equals(mode) ? liveHead.currentLiveSequence() : 0;
        IngestionFeedCursor cursor = cursors.findById(1).orElseThrow();
        if ("LIVE".equals(mode)) cursor.selectLive(headSequence);
        else cursor.selectDemo(runId);
        ConsumerSelection selection = selections.findById(1).orElseThrow(); selection.select(mode, runId, clock.instant());
    }
}
