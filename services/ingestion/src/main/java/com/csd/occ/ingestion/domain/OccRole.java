package com.csd.occ.ingestion.domain;

import java.util.Locale;

public enum OccRole {
    ADMIN, CONTROLLER, CAPTAIN, UNASSIGNED;

    public static OccRole normalized(String value) {
        if (value == null) return UNASSIGNED;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return UNASSIGNED;
        }
    }
}
