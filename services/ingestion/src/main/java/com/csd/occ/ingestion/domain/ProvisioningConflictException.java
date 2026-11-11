package com.csd.occ.ingestion.domain;
public class ProvisioningConflictException extends RuntimeException {
    public ProvisioningConflictException() { super("Provisioning conflicts with an existing account or request"); }
}
