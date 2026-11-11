package com.csd.occ.ingestion.domain;

import java.time.*;
import java.util.*;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class ProvisioningService {
    private final ProvisioningTransactionService transactions;
    private final ProvisioningOperationRepository operations;
    private final IdentityProviderPort provider;
    private final Clock clock;
    public ProvisioningService(ProvisioningTransactionService transactions,ProvisioningOperationRepository operations,IdentityProviderPort provider,Clock clock) {
        this.transactions=transactions; this.operations=operations; this.provider=provider; this.clock=clock;
    }
    public OperationSnapshot submit(DirectoryActor actor,String key,ProvisioningRequest request) {
        actor.requireAdmin();
        var admission=transactions.admit(actor,key,request);
        var operation=admission.operation();
        if(!admission.dispatch()) return result(operation);
        IdentityProviderPort.User user;
        try { user=provider.createUser(new IdentityProviderPort.CreateUser(operation.id(),request.displayName(),request.email(),request.role(),request.password())); }
        catch(IdentityProviderException failure) {
            if(failure.reason()==IdentityProviderException.Reason.UNAVAILABLE) return transactions.ready(operation.id());
            return result(transactions.reject(operation.id(),failure.reason().name()));
        }
        try {
            var confirmed=transactions.confirm(operation.id(),user,clock.instant());
            if(confirmed.status()!=ProvisioningStatus.SUCCEEDED) return transactions.ready(operation.id());
            return confirmed;
        } catch(RuntimeException localFailure) {
            // The provider may already have committed. Persisted pending state is the recovery authority.
            return transactions.ready(operation.id());
        }
    }
    public OperationSnapshot status(DirectoryActor actor,UUID id) { actor.requireAdmin(); return transactions.status(actor,id); }
    private OperationSnapshot result(OperationSnapshot operation) {
        if(operation.status()==ProvisioningStatus.REJECTED) {
            if("PASSWORD_POLICY".equals(operation.failureCode())) throw new IdentityProviderException(IdentityProviderException.Reason.PASSWORD_POLICY);
            throw new ProvisioningConflictException();
        }
        return operation;
    }
    /** Recovery has no password and never calls create. Only complete valid scans establish point-in-time absence. */
    public void reconcile() {
        Instant startedAt=clock.instant();
        var ids=new ArrayList<UUID>();
        for(int page=0;;page++) {
            var batch=operations.recoverable(startedAt,PageRequest.of(page,100)); ids.addAll(batch);
            if(batch.size()<100) break;
        }
        if(ids.isEmpty()) return;
        var users=new LinkedHashMap<UUID,IdentityProviderPort.User>();
        try {
            for(int page=1;;page++) {
                var batch=provider.listPage(page,1000);
                if(batch==null || batch.users().size()>1000) return;
                for(var user:batch.users()) if(users.putIfAbsent(user.id(),user)!=null) return;
                if(!batch.hasMore()) break;
                if(batch.users().isEmpty() || page>=1000) return;
            }
        } catch(IdentityProviderException failure) { return; }
        for(UUID id:ids) {
            var matches=users.values().stream().filter(user -> id.equals(user.provisioningOperationId())).toList();
            if(matches.size()==1) transactions.confirm(id,matches.getFirst(),clock.instant());
            else if(matches.isEmpty()) transactions.absent(id,startedAt);
        }
    }
    public record AccountSnapshot(UUID id,String displayName,String email,OccRole role,boolean available,Instant createdAt,Instant lastSignInAt,Instant synchronizedAt) { }
    public record OperationSnapshot(UUID id,String actorSubject,String email,String displayName,OccRole role,ProvisioningStatus status,
            Instant createdAt,Instant updatedAt,String failureCode,AccountSnapshot result) { }
}
