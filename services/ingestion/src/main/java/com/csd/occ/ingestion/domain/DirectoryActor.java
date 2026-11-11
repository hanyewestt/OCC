package com.csd.occ.ingestion.domain;

import java.util.Objects;

/** Created by the HTTP boundary from the verified authentication, never from request input. */
public record DirectoryActor(String subject, OccRole role) {
    public DirectoryActor {
        Objects.requireNonNull(subject);
        Objects.requireNonNull(role);
        if (subject.isBlank()) throw new DirectoryAccessDeniedException();
    }

    public void requireAdmin() {
        if (role != OccRole.ADMIN) throw new DirectoryAccessDeniedException();
    }

    public void requireDirectoryReader() {
        if (role != OccRole.ADMIN && role != OccRole.CONTROLLER) throw new DirectoryAccessDeniedException();
    }
}
