package com.csd.occ.planning.advisory.api.dto;

import com.csd.occ.planning.advisory.domain.AdvisoryCandidate;
import com.csd.occ.planning.advisory.domain.AdvisoryGroup;
import java.util.List;
import java.util.UUID;

public record AdvisoryGroupResponse(
        UUID id,
        UUID disruptionId,
        UUID demoRunId,
        String serviceId,
        int directionId,
        String hazardTitle,
        String hazardDescription,
        String hazardKind,
        String hazardClassification,
        Double latitude,
        Double longitude,
        String hazardLocation,
        String affectedStopName,
        String affectedStopCode,
        AdvisoryCandidate candidate,
        String preview,
        List<AdvisoryResponse> members) {

    public static AdvisoryGroupResponse from(AdvisoryGroup group) {
        var first = group.members().getFirst();
        var facts = first.context().facts();
        String description = facts != null && facts.description() != null ? facts.description()
                : facts != null ? facts.title() : "";
        String kind = facts != null && facts.kind() != null ? facts.kind() : "";
        Double latitude = null;
        Double longitude = null;
        String hazardLocation = null;
        if (facts != null && facts.locations() != null) {
            var loc = facts.locations().stream()
                    .filter(l -> l.latitude() != null && l.longitude() != null)
                    .findFirst();
            if (loc.isPresent()) {
                latitude = loc.get().latitude().doubleValue();
                longitude = loc.get().longitude().doubleValue();
                hazardLocation = loc.get().description();
            }
        }
        var exposures = first.context().exposures();
        String affectedStopName = exposures != null && !exposures.isEmpty() ? exposures.getFirst().stopName() : null;
        String affectedStopCode = exposures != null && !exposures.isEmpty() ? exposures.getFirst().stopCode() : null;

        return new AdvisoryGroupResponse(
                first.id(),
                group.key().disruptionId(),
                group.key().demoRunId(),
                group.key().serviceId(),
                group.key().directionId(),
                facts != null ? facts.title() : "",
                description,
                kind,
                first.context().classification(),
                latitude,
                longitude,
                hazardLocation,
                affectedStopName,
                affectedStopCode,
                first.candidate(),
                first.preview(),
                group.members().stream().map(AdvisoryResponse::from).toList());
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "AdvisoryGroupPageResponse")
    public record PageResponse(List<AdvisoryGroupResponse> items, int page, int size, long totalItems, int totalPages) {}
}
