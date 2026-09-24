package com.csd.occ.ingestion.domain;

import java.io.Serializable;
import java.util.Objects;

public class FeedStatusId implements Serializable {
    private String source;
    private String scopeKey;

    public FeedStatusId() {
    }

    public FeedStatusId(String source, String scopeKey) {
        this.source = source;
        this.scopeKey = scopeKey;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FeedStatusId id && Objects.equals(source, id.source)
                && Objects.equals(scopeKey, id.scopeKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, scopeKey);
    }
}
