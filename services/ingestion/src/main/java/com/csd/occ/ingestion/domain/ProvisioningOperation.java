package com.csd.occ.ingestion.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="provisioning_operation",schema="ingestion")
public class ProvisioningOperation {
    @Id private UUID id;
    private String actorSubject;
    private String idempotencyKey;
    private String email;
    private String displayName;
    @Enumerated(EnumType.STRING) private OccRole role;
    @Enumerated(EnumType.STRING) private ProvisioningStatus status;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant reconciliationAfter;
    @Version private long version;
    // Nullable until a provider-attributed account is confirmed; immutable original result on replay.
    private UUID accountId;
    private String resultName;
    private String resultEmail;
    private Instant accountCreatedAt;
    private Instant accountLastSignInAt;
    private Instant resultSynchronizedAt;
    // Nullable unless a fixed, safe provider rejection was confirmed.
    private String failureCode;
    protected ProvisioningOperation() { }
    public boolean matches(ProvisioningRequest request) { return email.equals(request.email()) && displayName.equals(request.displayName()) && role==request.role(); }
    public boolean accepts(IdentityProviderPort.User user) {
        return id.equals(user.provisioningOperationId()) && email.equals(user.email()) && role==user.role() && user.available();
    }
    public void ready(Instant now) { if(status==ProvisioningStatus.PENDING) { reconciliationAfter=now; updatedAt=now; } }
    public void passwordRequired(Instant scanStartedAt,Instant now) {
        if(status==ProvisioningStatus.PENDING && !updatedAt.isAfter(scanStartedAt) && !reconciliationAfter.isAfter(scanStartedAt)) {
            status=ProvisioningStatus.PASSWORD_REQUIRED; updatedAt=now;
        }
    }
    public void reject(String code,Instant now) { if(status==ProvisioningStatus.PENDING) { status=ProvisioningStatus.REJECTED; failureCode=code; updatedAt=now; } }
    public void succeed(IdentityProviderPort.User user,Instant now) {
        if(status==ProvisioningStatus.SUCCEEDED || status==ProvisioningStatus.REJECTED) return;
        if(!accepts(user)) throw new IllegalArgumentException("Unconfirmed provisioning result");
        status=ProvisioningStatus.SUCCEEDED; accountId=user.id(); resultName=user.displayName(); resultEmail=user.email();
        accountCreatedAt=user.createdAt(); accountLastSignInAt=user.lastSignInAt(); resultSynchronizedAt=now; failureCode=null; updatedAt=now;
    }
    public ProvisioningService.OperationSnapshot snapshot() {
        var result=accountId==null ? null : new ProvisioningService.AccountSnapshot(accountId,resultName,resultEmail,role,true,accountCreatedAt,accountLastSignInAt,resultSynchronizedAt);
        return new ProvisioningService.OperationSnapshot(id,actorSubject,email,displayName,role,status,createdAt,updatedAt,failureCode,result);
    }
    public UUID id() { return id; }
    public ProvisioningStatus status() { return status; }

}
