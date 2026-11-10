package com.csd.occ.ingestion.domain;

public class DirectoryAccessDeniedException extends RuntimeException {
    public DirectoryAccessDeniedException() { super("This role cannot access the user directory"); }
}
