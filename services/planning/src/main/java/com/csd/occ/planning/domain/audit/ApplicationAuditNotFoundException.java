package com.csd.occ.planning.domain.audit;

public class ApplicationAuditNotFoundException extends RuntimeException {
    public ApplicationAuditNotFoundException() { super("Audit event not found"); }
}
