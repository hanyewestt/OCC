package com.csd.occ.ingestion.domain;

import java.util.UUID;

public final class DemoRunNotFoundException extends RuntimeException {
    public DemoRunNotFoundException(UUID id) { super("Demo run " + id + " was not found"); }
}
