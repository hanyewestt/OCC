package com.csd.occ.planning.advisory.domain;

import java.util.Map;

public final class AssignmentOperationConflictException extends RuntimeException {
    private final Map<String, Object> details;
    public AssignmentOperationConflictException(String message, Map<String, Object> details) {
        super(message); this.details = details == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(details));
    }
    public Map<String, Object> details() { return details; }
}
