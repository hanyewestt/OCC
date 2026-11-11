package com.csd.occ.ingestion.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface IdentityProviderPort {
    UserPage listPage(int page, int size);
    Optional<User> get(UUID id);
    User createUser(CreateUser request);

    /** Only normalized identity fields and the server-managed operation ID cross this boundary. */
    record User(UUID id, String displayName, String email, OccRole role, boolean available,
                Instant createdAt, Instant lastSignInAt, UUID provisioningOperationId) {
        public User {
            Objects.requireNonNull(id);
            Objects.requireNonNull(displayName);
            Objects.requireNonNull(email);
            Objects.requireNonNull(role);
            Objects.requireNonNull(createdAt);
        }
    }

    record UserPage(List<User> users, boolean hasMore) {
        public UserPage { users = List.copyOf(users); }
    }

    record CreateUser(UUID operationId, String displayName, String email, OccRole role, String password) {
        public CreateUser {
            Objects.requireNonNull(operationId);
            Objects.requireNonNull(displayName);
            Objects.requireNonNull(email);
            Objects.requireNonNull(password);
            if (role != OccRole.CONTROLLER && role != OccRole.CAPTAIN)
                throw new IllegalArgumentException("Only CONTROLLER or CAPTAIN accounts may be created");
        }

        @Override public String toString() { return "CreateUser[operationId=" + operationId + ", role=" + role + ", password=REDACTED]"; }
    }
}
