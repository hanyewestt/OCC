package com.csd.occ.ingestion.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "feed_status", schema = "ingestion")
@IdClass(FeedStatusId.class)
public class FeedStatus {
    protected FeedStatus() {
    }

    @Id private String source;
    @Id private String scopeKey;
    private String status;
    private Instant lastAttemptAt;
    private Instant lastSuccessAt;
    private Integer consecutiveFailures;
    private Integer staleAfterSeconds;
    private String message;
    private Instant updatedAt;

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getScopeKey() { return scopeKey; }
    public void setScopeKey(String scopeKey) { this.scopeKey = scopeKey; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public void setLastAttemptAt(Instant lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public void setLastSuccessAt(Instant lastSuccessAt) { this.lastSuccessAt = lastSuccessAt; }
    public Integer getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(Integer consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }
    public Integer getStaleAfterSeconds() { return staleAfterSeconds; }
    public void setStaleAfterSeconds(Integer staleAfterSeconds) { this.staleAfterSeconds = staleAfterSeconds; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
