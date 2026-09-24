package com.csd.occ.ingestion.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "poll_target", schema = "ingestion")
@IdClass(PollTargetId.class)
public class PollTarget {
    protected PollTarget() {}
    
    @Id
    private String source;
    @Id
    private String scopeKey;
    
    private String stopCode;
    private Boolean enabled;
    private Integer pollIntervalSeconds;
    private Instant createdAt;
    private Instant updatedAt;

    // Getters and setters
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getScopeKey() { return scopeKey; }
    public void setScopeKey(String scopeKey) { this.scopeKey = scopeKey; }
    public String getStopCode() { return stopCode; }
    public void setStopCode(String stopCode) { this.stopCode = stopCode; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Integer getPollIntervalSeconds() { return pollIntervalSeconds; }
    public void setPollIntervalSeconds(Integer pollIntervalSeconds) { this.pollIntervalSeconds = pollIntervalSeconds; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
