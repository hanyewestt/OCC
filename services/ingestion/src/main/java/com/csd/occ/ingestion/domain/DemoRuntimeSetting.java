package com.csd.occ.ingestion.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "demo_runtime_setting", schema = "ingestion")
public class DemoRuntimeSetting {
    protected DemoRuntimeSetting() {}

    @Id private Integer id;
    private boolean enabled;
    private Instant changedAt;

    public boolean enabled() { return enabled; }
    public void setEnabled(boolean enabled, Instant changedAt) {
        this.enabled = enabled;
        this.changedAt = changedAt;
    }
}
