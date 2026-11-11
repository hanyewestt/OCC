package com.csd.occ.ingestion.domain;

public class DirectoryUserNotFoundException extends RuntimeException {
    public DirectoryUserNotFoundException() { super("Directory user not found"); }
}
