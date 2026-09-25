package com.csd.occ.operations.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("occ.demo-fleet")
public record DemoFleetProperties(int defaultPageSize, int maxPageSize) {
    public DemoFleetProperties {
        if (defaultPageSize < 1 || maxPageSize < defaultPageSize) {
            throw new IllegalArgumentException("Demo fleet page sizes must be positive and default must not exceed maximum");
        }
    }
}
