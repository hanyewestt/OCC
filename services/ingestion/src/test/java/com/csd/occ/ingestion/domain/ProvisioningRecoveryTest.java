package com.csd.occ.ingestion.domain;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
class ProvisioningRecoveryTest {
    @Test void malformedSnapshotNeverTransitionsOperations() {
        var provider=mock(IdentityProviderPort.class); var transactions=mock(ProvisioningTransactionService.class);
        var operations=mock(ProvisioningOperationRepository.class); Instant now=Instant.parse("2026-11-11T00:00:00Z");
        when(operations.recoverable(eq(now),any(Pageable.class))).thenReturn(List.of(UUID.randomUUID()));
        when(provider.listPage(1,1000)).thenReturn(null);
        var service=new ProvisioningService(transactions,operations,provider,Clock.fixed(now,ZoneOffset.UTC));
        assertThatCode(service::reconcile).doesNotThrowAnyException(); verifyNoInteractions(transactions);
    }
    @Test void endlessProviderPagesFailClosedAtBoundedScanWithoutPublishingAbsence() {
        var provider=mock(IdentityProviderPort.class); var transactions=mock(ProvisioningTransactionService.class);
        var operations=mock(ProvisioningOperationRepository.class); Instant now=Instant.parse("2026-11-11T00:00:00Z");
        when(operations.recoverable(eq(now),any(Pageable.class))).thenReturn(List.of(UUID.randomUUID()));
        when(provider.listPage(anyInt(),eq(1000))).thenAnswer(call -> {
            int page=call.getArgument(0); if(page>1000) throw new AssertionError("Unbounded provider scan");
            return new IdentityProviderPort.UserPage(List.of(new IdentityProviderPort.User(UUID.randomUUID(),"Captain","a@b.test",OccRole.CAPTAIN,true,now,null,null)),true);
        });
        var service=new ProvisioningService(transactions,operations,provider,Clock.fixed(now,ZoneOffset.UTC));
        assertThatCode(service::reconcile).doesNotThrowAnyException(); verifyNoInteractions(transactions);
    }
}
