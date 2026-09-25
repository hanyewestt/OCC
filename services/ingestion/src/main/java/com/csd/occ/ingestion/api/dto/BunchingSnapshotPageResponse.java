package com.csd.occ.ingestion.api.dto;

import java.util.List;

public record BunchingSnapshotPageResponse(List<BunchingSnapshotResponse> items, long nextSequence) {
    public BunchingSnapshotPageResponse { items = List.copyOf(items); }
}
