package com.csd.occ.ingestion.domain;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short independent transactions; no provider HTTP call occurs here. */
@Service
public class ProvisioningTransactionService {
    private final ProvisioningOperationRepository operations;
    private final OccUserRepository users;
    private final DirectorySynchronizationRepository checkpoints;
    private final ApplicationAuditEventRepository audit;
    private final Clock clock;
    public ProvisioningTransactionService(ProvisioningOperationRepository operations,OccUserRepository users,
            DirectorySynchronizationRepository checkpoints,ApplicationAuditEventRepository audit,Clock clock) {
        this.operations=operations; this.users=users; this.checkpoints=checkpoints; this.audit=audit; this.clock=clock;
    }
    @Transactional public Admission admit(DirectoryActor actor,String key,ProvisioningRequest request) {
        actor.requireAdmin(); validateKey(key);
        var existing=operations.findByActorSubjectAndIdempotencyKey(actor.subject(),key);
        if(existing.isPresent()) return replay(existing.get(),request);
        UUID id=UUID.randomUUID(); Instant now=clock.instant();
        // Crash dispatch grace exceeds the maximum 120-second provider timeout; never permits create retry.
        if(operations.reserve(id,actor.subject(),key,request.email(),request.displayName(),request.role().name(),now,now.plusSeconds(125))==0) {
            return replay(operations.findByActorSubjectAndIdempotencyKey(actor.subject(),key).orElseThrow(ProvisioningConflictException::new),request);
        }
        var operation=operations.findById(id).orElseThrow();
        return new Admission(operation.snapshot(),true);
    }
    private Admission replay(ProvisioningOperation operation,ProvisioningRequest request) {
        if(!operation.matches(request)) throw new ProvisioningConflictException();
        return new Admission(operation.snapshot(),false);
    }
    private void validateKey(String key) {
        if(key==null || key.isBlank() || key.length()>200) throw new IllegalArgumentException("Idempotency-Key must contain 1 to 200 characters");
    }
    @Transactional(readOnly=true) public ProvisioningService.OperationSnapshot status(DirectoryActor actor,UUID id) {
        actor.requireAdmin(); return operations.findById(id).orElseThrow(DirectoryUserNotFoundException::new).snapshot();
    }
    @Transactional public ProvisioningService.OperationSnapshot ready(UUID id) {
        var operation=operations.lock(id).orElseThrow(); operation.ready(clock.instant());
        if(operation.status()==ProvisioningStatus.PENDING) record(operation,"PENDING","PROVIDER_UNCERTAIN");
        return operation.snapshot();
    }
    @Transactional public ProvisioningService.OperationSnapshot reject(UUID id,String code) {
        var operation=operations.lock(id).orElseThrow();
        if(operation.status()==ProvisioningStatus.PENDING) {
            operation.reject(code,clock.instant()); record(operation,"FAILURE",code);
        }
        return operation.snapshot();
    }
    @Transactional public ProvisioningService.OperationSnapshot confirm(UUID id,IdentityProviderPort.User user,Instant observedAt) {
        var operation=operations.lock(id).orElseThrow();
        if(operation.status()==ProvisioningStatus.SUCCEEDED || operation.status()==ProvisioningStatus.REJECTED) return operation.snapshot();
        if(!operation.accepts(user)) return operation.snapshot();
        checkpoints.lockCheckpoint();
        Instant publishedAt=clock.instant();
        var row=users.findById(user.id());
        if(row.isEmpty()) users.save(new OccUser(user,observedAt,publishedAt));
        else if(!row.get().observedAt().isAfter(observedAt)) row.get().update(user,observedAt,publishedAt);
        operation.succeed(user,publishedAt); record(operation,"SUCCESS","CONFIRMED");
        return operation.snapshot();
    }
    @Transactional public void absent(UUID id,Instant scanStartedAt) {
        operations.lock(id).ifPresent(operation -> {
            var before=operation.status(); operation.passwordRequired(scanStartedAt,clock.instant());
            if(before==ProvisioningStatus.PENDING && operation.status()==ProvisioningStatus.PASSWORD_REQUIRED)
                record(operation,"PASSWORD_REQUIRED","NO_MATCH_AT_SCAN");
        });
    }
    private void record(ProvisioningOperation operation,String outcome,String code) {
        String sourceKey="provisioning:"+operation.id()+":"+outcome;
        if(audit.existsBySourceKey(sourceKey)) return;
        var snapshot=operation.snapshot();
        var details=new LinkedHashMap<String,String>(); details.put("operationId",snapshot.id().toString()); details.put("requestedRole",snapshot.role().name()); details.put("code",code);
        if(snapshot.result()!=null) details.put("accountId",snapshot.result().id().toString());
        audit.save(new ApplicationAuditEvent(UUID.randomUUID(),clock.instant(),clock.instant(),"ACCOUNT_ASSIGNMENT","ACCOUNT_PROVISIONING",outcome,
                "INGESTION",snapshot.actorSubject(),"USER","PROVISIONING_OPERATION",snapshot.id().toString(),null,null,null,snapshot.id().toString(),
                switch(outcome) { case "SUCCESS" -> "Account provisioning confirmed"; case "PENDING" -> "Account provisioning is uncertain"; case "PASSWORD_REQUIRED" -> "Account provisioning requires a fresh password submission"; default -> "Account provisioning rejected"; },details,sourceKey));
    }
    public record Admission(ProvisioningService.OperationSnapshot operation,boolean dispatch) { }
}
