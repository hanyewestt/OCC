package com.csd.occ.operations.domain;

public class DemoRunNotFoundException extends RuntimeException {
    public DemoRunNotFoundException() { super("Demo run not found"); }
}
