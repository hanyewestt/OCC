package com.csd.occ.ingestion.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserDirectoryService {
    private final IdentityProviderPort provider;
    private final DirectoryProjectionService projection;
    private final OccUserRepository users;
    private final DirectorySynchronizationRepository checkpoints;
    private final DemoRunRepository runs;
    private final DemoVehicleAssignmentRepository assignments;
    private final Clock clock;

    public UserDirectoryService(IdentityProviderPort provider, DirectoryProjectionService projection,
            OccUserRepository users, DirectorySynchronizationRepository checkpoints, DemoRunRepository runs,
            DemoVehicleAssignmentRepository assignments, Clock clock) {
        this.provider = provider; this.projection = projection; this.users = users; this.checkpoints = checkpoints;
        this.runs = runs; this.assignments = assignments; this.clock = clock;
    }

    public Instant synchronize(DirectoryActor actor) { actor.requireAdmin(); return synchronize(); }

    /** Trusted infrastructure entry point; HTTP callers use the actor-checked overload. No transaction spans HTTP. */
    public Instant synchronize() {
        Instant startedAt = clock.instant();
        var snapshot = new LinkedHashMap<UUID, IdentityProviderPort.User>();
        for (int page = 1; ; page++) {
            var batch = provider.listPage(page, 1000);
            for (var user : batch.users()) {
                if (snapshot.putIfAbsent(user.id(), user) != null)
                    throw new IdentityProviderException(IdentityProviderException.Reason.UNAVAILABLE);
            }
            if (!batch.hasMore()) break;
            if (batch.users().isEmpty() || page == Integer.MAX_VALUE)
                throw new IdentityProviderException(IdentityProviderException.Reason.UNAVAILABLE);
        }
        return projection.publish(startedAt, List.copyOf(snapshot.values()));
    }

    /** Eligibility is always confirmed directly against the provider; an unavailable provider throws safely. */
    public Optional<IdentityProviderPort.User> availableCaptain(UUID id) {
        return provider.get(id).filter(user -> user.available() && user.role() == OccRole.CAPTAIN);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DirectoryPage<UserSnapshot> list(DirectoryActor actor, String search, OccRole role, int page, int size) {
        actor.requireAdmin();
        return query(search, role, null, page, size, Function.identity());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UserSnapshot detail(DirectoryActor actor, UUID id) {
        actor.requireAdmin();
        var user = users.findById(id).orElseThrow(DirectoryUserNotFoundException::new);
        return snapshot(user, currentAssignments(List.of(id)).get(id));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DirectoryPage<CaptainSnapshot> captains(DirectoryActor actor, String search, Boolean available, int page, int size) {
        actor.requireDirectoryReader();
        return query(search, OccRole.CAPTAIN, available, page, size,
                user -> new CaptainSnapshot(user.id(), user.displayName(), user.email(), user.currentAssignment()));
    }

    private <T> DirectoryPage<T> query(String search, OccRole role, Boolean available,
            int page, int size, Function<UserSnapshot, T> mapper) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Page must be nonnegative and size between 1 and 100");
        Specification<OccUser> specification = (root, query, cb) -> {
            var clauses = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (role != null) clauses.add(cb.equal(root.get("role"), role));
            if (available != null) clauses.add(cb.equal(root.get("available"), available));
            if (search != null && !search.isBlank()) {
                String literal = search.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                clauses.add(cb.or(cb.like(cb.lower(root.get("displayName")), "%" + literal + "%", '\\'),
                        cb.like(cb.lower(root.get("email")), "%" + literal + "%", '\\')));
            }
            return cb.and(clauses.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        var found = users.findAll(specification, PageRequest.of(page, size, Sort.by("displayName", "id")));
        var current = currentAssignments(found.getContent().stream().map(OccUser::id).toList());
        Instant checkpoint = checkpoints.findById((short) 1).map(DirectorySynchronization::synchronizedAt).orElse(null);
        return new DirectoryPage<>(found.getContent().stream().map(user -> mapper.apply(snapshot(user, current.get(user.id())))).toList(),
                found.getNumber(), found.getSize(), found.getTotalElements(), found.getTotalPages(), checkpoint);
    }

    private Map<UUID, CurrentAssignment> currentAssignments(List<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return runs.findActive().filter(run -> List.of("RUNNING", "REPLAY_COMPLETE").contains(run.status()))
                .map(run -> assignments.findByIdDemoRunIdAndCaptainUserIdIn(run.id(), ids).stream()
                        .collect(Collectors.toMap(assignment -> assignment.captainUserId().orElseThrow(),
                                assignment -> new CurrentAssignment(run.id(), assignment.busId(), assignment.vehicleRegistrationNumber(),
                                        assignment.serviceId(), assignment.directionId())))).orElse(Map.of());
    }

    private UserSnapshot snapshot(OccUser user, CurrentAssignment assignment) {
        return new UserSnapshot(user.id(), user.displayName(), user.email(), user.role(), user.available(),
                user.createdAt(), user.lastSignInAt(), user.synchronizedAt(), assignment);
    }

    public record DirectoryPage<T>(List<T> items, int page, int size, long totalItems, int totalPages, Instant directorySynchronizedAt) { }
    public record CurrentAssignment(UUID demoRunId, String busId, String vehicleRegistrationNumber, String serviceId, int directionId) { }
    public record UserSnapshot(UUID id, String displayName, String email, OccRole role, boolean available,
            Instant createdAt, Instant lastSignInAt, Instant synchronizedAt, CurrentAssignment currentAssignment) { }
    public record CaptainSnapshot(UUID id, String displayName, String email, CurrentAssignment currentAssignment) { }
}
