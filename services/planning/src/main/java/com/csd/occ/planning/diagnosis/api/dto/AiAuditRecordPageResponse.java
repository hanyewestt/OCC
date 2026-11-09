package com.csd.occ.planning.diagnosis.api.dto;

import java.util.List;

public record AiAuditRecordPageResponse(List<AiAuditRecordResponse> items, int page, int size,
                                        long totalItems, int totalPages) { }
