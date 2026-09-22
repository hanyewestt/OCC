package sg.edu.smu.occ.ingestion.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.io.Serializable;
import java.util.Objects;

class PollTargetId implements Serializable {
    private String source;
    private String scopeKey;

    public PollTargetId() {}
    public PollTargetId(String source, String scopeKey) {
        this.source = source;
        this.scopeKey = scopeKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PollTargetId that = (PollTargetId) o;
        return Objects.equals(source, that.source) && Objects.equals(scopeKey, that.scopeKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, scopeKey);
    }
}

@Entity
@Table(name = "poll_target", schema = "ingestion")
@IdClass(PollTargetId.class)
public class PollTarget {
    
    @Id
    private String source;
    @Id
    private String scopeKey;
    
    private String stopCode;
    private Boolean enabled;
    private Integer pollIntervalSeconds;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

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
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
