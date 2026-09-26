package com.csd.occ.ingestion.domain;

import java.util.List;

public record LiveBunchingAlertPage(List<LiveBunchingAlert> items, int page, int size, long totalItems,
                                    int totalPages) {
    public LiveBunchingAlertPage { items = List.copyOf(items); }
}
