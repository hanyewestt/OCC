package com.csd.occ.planning.advisory.infrastructure.http;

import com.csd.occ.planning.advisory.domain.AssignmentIngestionPort;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpAssignmentIngestionAdapter implements AssignmentIngestionPort {
    private final RestClient ingestion;
    public HttpAssignmentIngestionAdapter(@Qualifier("ingestionContextRestClient") RestClient ingestion) {
        this.ingestion = ingestion;
    }

    @Override
    public Receipt apply(Command command) {
        AssignmentIngestionPort.Receipt receipt = ingestion.put()
                .uri("/internal/v1/ingestion/demo-runs/{runId}/buses/{busId}/assignment-operations/{operationId}",
                        command.runId(), command.busId(), command.operationId())
                .body(new CommandBody(command.captainUserId(), command.expectedVersion(), command.actorSubject()))
                .retrieve()
                .onStatus(status -> status.value() == 409, (request, response) -> { })
                .onStatus(HttpStatusCode::isError,
                        (request, response) -> { throw new AssignmentDispatchException(response.getStatusCode()); })
                .body(Receipt.class);
        if (receipt == null || !command.operationId().equals(receipt.operationId())
                || !command.runId().equals(receipt.runId()) || !command.busId().equals(receipt.busId())
                || !java.util.Set.of("APPLIED", "NO_OP", "REJECTED").contains(receipt.outcome()))
            throw new AssignmentDispatchException(HttpStatusCode.valueOf(502));
        return receipt;
    }

    private record CommandBody(UUID captainUserId, long expectedVersion, String actorSubject) { }
}
