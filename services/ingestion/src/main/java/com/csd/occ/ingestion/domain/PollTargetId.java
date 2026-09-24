package com.csd.occ.ingestion.domain;

import java.io.Serializable;
import java.util.Objects;

public class PollTargetId implements Serializable {
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

