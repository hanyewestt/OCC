package com.csd.occ.ingestion.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/** Transient only: the credential must never enter persistence or audit values. */
public record ProvisioningRequest(String displayName, String email, OccRole role, String password) {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    public ProvisioningRequest {
        if (displayName == null || email == null || password == null) throw invalid();
        displayName = displayName.trim();
        email = email.trim().toLowerCase(Locale.ROOT);
        int length = password.codePointCount(0, password.length());
        if (displayName.isBlank() || displayName.length() > 200 || email.length() > 254
                || !EMAIL.matcher(email).matches() || length < 15 || length > 128
                || (role != OccRole.CONTROLLER && role != OccRole.CAPTAIN)) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("One or more provisioning values are invalid"); }
    @Override public String toString() { return "ProvisioningRequest[role=" + role + ", password=REDACTED]"; }
}
