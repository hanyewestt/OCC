package com.csd.occ.ingestion.api.dto;

import java.util.List;

public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
}
