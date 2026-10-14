package com.csd.occ.disruption.bunching.infrastructure.ingestion;

import com.csd.occ.disruption.bunching.domain.IngestionFeedHeadPort;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpLiveFeedHeadAdapter implements IngestionFeedHeadPort {
    private final RestClient ingestion;

    public HttpLiveFeedHeadAdapter(RestClient ingestionRestClient) {
        this.ingestion = ingestionRestClient;
    }

    @Override
    public long currentLiveSequence() {
        FeedHead head = ingestion.get().uri("/internal/v1/arrival-snapshots/head").retrieve().body(FeedHead.class);
        if (head == null || head.sequence() < 0) throw new IllegalStateException("Ingestion returned an invalid feed head");
        return head.sequence();
    }

    private record FeedHead(long sequence) {}
}
