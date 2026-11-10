package com.csd.occ.ingestion.domain;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserDirectoryServiceTest {
    private final IdentityProviderPort provider = mock(IdentityProviderPort.class);
    private final DirectoryProjectionService projection = mock(DirectoryProjectionService.class);
    private final OccUserRepository users = mock(OccUserRepository.class);
    private final DirectorySynchronizationRepository checkpoints = mock(DirectorySynchronizationRepository.class);
    private final DemoRunRepository runs = mock(DemoRunRepository.class);
    private final DemoVehicleAssignmentRepository assignments = mock(DemoVehicleAssignmentRepository.class);
    private final Instant now = Instant.parse("2026-11-10T00:00:00Z");
    private final DirectoryActor admin = new DirectoryActor("verified-admin", OccRole.ADMIN);
    private final UserDirectoryService directory = new UserDirectoryService(provider, projection, users,
            checkpoints, runs, assignments, Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void scansEveryPageBeforePublishingTheCompleteSnapshot() {
        var first = user(OccRole.CAPTAIN, true);
        var second = user(OccRole.ADMIN, true);
        when(provider.listPage(1, 1000)).thenReturn(new IdentityProviderPort.UserPage(List.of(first), true));
        when(provider.listPage(2, 1000)).thenReturn(new IdentityProviderPort.UserPage(List.of(second), false));
        when(projection.publish(now, List.of(first, second))).thenReturn(now);
        assertThat(directory.synchronize(admin)).isEqualTo(now);
        var order = inOrder(provider, projection);
        order.verify(provider).listPage(1, 1000);
        order.verify(provider).listPage(2, 1000);
        order.verify(projection).publish(now, List.of(first, second));
    }

    @Test
    void failedPartialScanNeverPublishesOrMarksPreviousUsersMissing() {
        when(provider.listPage(1, 1000)).thenReturn(new IdentityProviderPort.UserPage(List.of(user(OccRole.CAPTAIN, true)), true));
        when(provider.listPage(2, 1000)).thenThrow(new IdentityProviderException(IdentityProviderException.Reason.UNAVAILABLE));
        assertThatThrownBy(() -> directory.synchronize(admin)).isInstanceOf(IdentityProviderException.class);
        verifyNoInteractions(projection, users, checkpoints);
    }

    @Test
    void duplicatedProviderPageFailsClosedWithoutPublishing() {
        var user = user(OccRole.CAPTAIN, true);
        when(provider.listPage(1, 1000)).thenReturn(new IdentityProviderPort.UserPage(List.of(user), true));
        when(provider.listPage(2, 1000)).thenReturn(new IdentityProviderPort.UserPage(List.of(user), false));
        assertThatThrownBy(() -> directory.synchronize(admin)).isInstanceOf(IdentityProviderException.class);
        verifyNoInteractions(projection);
    }

    @Test
    void directoryMutationsRecheckTheAuthenticatedActorRole() {
        assertThatThrownBy(() -> directory.synchronize(new DirectoryActor("controller", OccRole.CONTROLLER)))
                .isInstanceOf(DirectoryAccessDeniedException.class);
        verifyNoInteractions(provider, projection);
    }

    @Test
    void eligibilityUsesCurrentProviderStateAndRejectsUnavailableOrWrongRoles() {
        for (var user : List.of(user(OccRole.ADMIN, true), user(OccRole.CONTROLLER, true),
                user(OccRole.UNASSIGNED, true), user(OccRole.CAPTAIN, false))) {
            when(provider.get(user.id())).thenReturn(Optional.of(user));
            assertThat(directory.availableCaptain(user.id())).isEmpty();
        }
        var captain = user(OccRole.CAPTAIN, true);
        when(provider.get(captain.id())).thenReturn(Optional.of(captain));
        assertThat(directory.availableCaptain(captain.id())).contains(captain);
        verifyNoInteractions(users);
    }

    @Test
    void captainAndUnassignedActorsCannotReadTheDirectory() {
        for (var role : List.of(OccRole.CAPTAIN, OccRole.UNASSIGNED)) {
            assertThatThrownBy(() -> directory.captains(new DirectoryActor("actor", role), null, null, 0, 20))
                    .isInstanceOf(DirectoryAccessDeniedException.class);
            assertThatThrownBy(() -> directory.list(new DirectoryActor("actor", role), null, null, 0, 20))
                    .isInstanceOf(DirectoryAccessDeniedException.class);
        }
        verifyNoInteractions(users);
    }

    private IdentityProviderPort.User user(OccRole role, boolean available) {
        return new IdentityProviderPort.User(UUID.randomUUID(), "Synthetic user", "user@example.test", role,
                available, now.minusSeconds(100), null, null);
    }
}
