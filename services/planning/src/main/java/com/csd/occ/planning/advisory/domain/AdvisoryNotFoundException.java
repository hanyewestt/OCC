package com.csd.occ.planning.advisory.domain;

public class AdvisoryNotFoundException extends RuntimeException {
    public AdvisoryNotFoundException() { super("Advisory resource not found"); }
}
