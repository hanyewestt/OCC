package com.csd.occ.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PollTargetServiceTest {
    @Test
    void seedingCreatesServiceScopedTargets() {
        PollTargetRepository targets = mock(PollTargetRepository.class);
        ReferenceTopologyPort topology = mock(ReferenceTopologyPort.class);
        PollingConfigurationPort configuration = configuration();
        UUID version = UUID.randomUUID();
        when(topology.activeVersion()).thenReturn(Optional.of(new ReferenceTopologyPort.ReferenceTopologyVersion(version, "checksum")));
        when(topology.stopsForServices(version, List.of("190"))).thenReturn(List.of("44009"));
        when(topology.stopsForServices(version, List.of("972"))).thenReturn(List.of("44009", "44019"));
        Instant now = Instant.parse("2026-09-24T00:00:00Z");
        new PollTargetService(targets, topology, configuration, Clock.fixed(now, ZoneOffset.UTC)).seed();
        ArgumentCaptor<PollTarget> captured = ArgumentCaptor.forClass(PollTarget.class);
        verify(targets, times(3)).save(captured.capture());
        assertThat(captured.getAllValues()).extracting(PollTarget::getScopeKey)
                .containsExactlyInAnyOrder("service:190:stop:44009", "service:972:stop:44009", "service:972:stop:44019");
        assertThat(captured.getAllValues()).extracting(PollTarget::getServiceId)
                .containsExactlyInAnyOrder("190", "972", "972");
        assertThat(captured.getAllValues()).allSatisfy(target -> {
            assertThat(target.getCreatedAt()).isEqualTo(now);
            assertThat(target.getEnabled()).isTrue();
        });
    }

    @Test
    void existingTargetsSkipSeeding() {
        PollTargetRepository targets = mock(PollTargetRepository.class);
        ReferenceTopologyPort topology = mock(ReferenceTopologyPort.class);
        UUID version = UUID.randomUUID();
        when(topology.activeVersion()).thenReturn(Optional.of(new ReferenceTopologyPort.ReferenceTopologyVersion(version, "checksum")));
        when(topology.stopsForServices(version, List.of("190"))).thenReturn(List.of("44009"));
        when(topology.stopsForServices(version, List.of("972"))).thenReturn(List.of());
        when(targets.existsById(any())).thenReturn(true);
        new PollTargetService(targets, topology, configuration(), Clock.systemUTC()).seed();
        verify(targets, never()).save(any());
    }

    private PollingConfigurationPort configuration() {
        return new PollingConfigurationPort() {
            public List<String> monitoredServices() { return List.of("190", "972"); }
            public int staleAfterSeconds() { return 180; }
            public Duration retention() { return Duration.ofHours(24); }
        };
    }
}
