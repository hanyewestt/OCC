package com.csd.occ.ingestion.infrastructure.recommendation;

import com.csd.occ.ingestion.domain.PendingAssignmentOperationsPort;
import com.csd.occ.ingestion.infrastructure.config.DemoProperties;
import com.csd.occ.ingestion.infrastructure.security.PeerClientProperties;
import java.net.http.HttpClient;
import java.util.List;
import java.util.UUID;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpPendingAssignmentOperationsAdapter implements PendingAssignmentOperationsPort {
    private final RestClient planning;

    public HttpPendingAssignmentOperationsAdapter(DemoProperties settings, PeerClientProperties peers) {
        var requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(settings.planningTimeout()).build());
        requests.setReadTimeout(settings.planningTimeout());
        planning = RestClient.builder().baseUrl(settings.planningInternalUrl().toString())
                .defaultHeader("X-OCC-Peer-Token", peers.planningToken()).requestFactory(requests).build();
    }

    @Override
    public List<Pending> findPending(UUID runId) {
        var response = planning.get()
                .uri("/internal/v1/planning/demo-runs/{runId}/pending-assignment-operations", runId)
                .retrieve().body(PendingResponse.class);
        if (response == null || response.items() == null)
            throw new IllegalStateException("Planning pending assignment response is incomplete");
        return response.items().stream()
                .map(item -> new Pending(item.busId(), item.operationId(), item.status())).toList();
    }

    private record PendingResponse(List<PendingItem> items) { }
    private record PendingItem(String busId, UUID operationId, String status) { }
}
