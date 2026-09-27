package com.csd.occ.ingestion.infrastructure.bunching;

import com.csd.occ.ingestion.domain.BunchingControlPort;
import com.csd.occ.ingestion.domain.DemoBunchingAlert;
import com.csd.occ.ingestion.domain.DemoBunchingUnavailableException;
import com.csd.occ.ingestion.domain.LiveBunchingAlert;
import com.csd.occ.ingestion.domain.LiveBunchingAlertPage;
import com.csd.occ.ingestion.infrastructure.config.DemoProperties;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class HttpBunchingControlAdapter implements BunchingControlPort {
    private final RestClient bunching;

    public HttpBunchingControlAdapter(DemoProperties settings) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(settings.bunchingControlTimeout()).build());
        requests.setReadTimeout(settings.bunchingControlTimeout());
        this.bunching = RestClient.builder().baseUrl(settings.bunchingInternalUrl().toString())
                .requestFactory(requests).build();
    }

    @Override
    public void selectDemo(UUID runId) {
        bunching.put().uri("/internal/v1/consumer-selection")
                .body(new SelectionRequest("DEMO", runId)).retrieve().toBodilessEntity();
    }

    @Override
    public void selectLive() {
        bunching.put().uri("/internal/v1/consumer-selection")
                .body(new SelectionRequest("LIVE", null)).retrieve().toBodilessEntity();
    }

    @Override
    public List<DemoBunchingAlert> alerts(UUID runId) {
        AlertPage page = bunching.get().uri("/internal/v1/demo-runs/{id}/alerts", runId)
                .retrieve().body(AlertPage.class);
        return page == null ? List.of() : page.alerts().stream()
                .map(alert -> new DemoBunchingAlert(alert.eventId(), alert.bunchingEventId(), alert.action(), alert.serviceId(), alert.stopCode(),
                        alert.directionId(), alert.detectedAt(), alert.classification(),
                        alert.leadingObservationId(), alert.followingObservationId())).toList();
    }

    @Override
    public LiveBunchingAlertPage currentLiveAlerts(int page, int size) {
        try {
            LiveAlertPage response = bunching.get().uri(builder -> builder.path("/api/v1/live-bunching-alerts")
                    .queryParam("page", page).queryParam("size", size).build()).retrieve().body(LiveAlertPage.class);
            if (response == null) return new LiveBunchingAlertPage(List.of(), page, size, 0, 0);
            return new LiveBunchingAlertPage(response.items().stream().map(alert -> new LiveBunchingAlert(alert.eventId(),
                    alert.alertId(), alert.action(), alert.classification(), alert.serviceId(), alert.stopCode(),
                    alert.directionId(), alert.detectedAt(), alert.fetchedAt(), alert.rawObservationId(),
                    alert.pollRunId(), alert.evidence())).toList(), response.page(), response.size(),
                    response.totalItems(), response.totalPages());
        } catch (RestClientException exception) {
            throw new DemoBunchingUnavailableException();
        }
    }

    private record SelectionRequest(String mode, UUID demoRunId) {}
    private record AlertPage(List<AlertResponse> alerts) {
        private AlertPage { alerts = List.copyOf(alerts); }
    }
    private record AlertResponse(UUID eventId, UUID bunchingEventId, String action, String serviceId, String stopCode, int directionId,
                                 Instant detectedAt, String evidence, String classification,
                                 UUID leadingObservationId, UUID followingObservationId) {}
    private record LiveAlertPage(List<LiveAlertResponse> items, int page, int size, long totalItems, int totalPages) {
        private LiveAlertPage { items = List.copyOf(items); }
    }
    private record LiveAlertResponse(UUID eventId, UUID alertId, String action, String classification,
                                     String serviceId, String stopCode, int directionId, Instant detectedAt,
                                     Instant fetchedAt, UUID rawObservationId, UUID pollRunId, String evidence) {}
}
