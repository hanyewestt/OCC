package com.csd.occ.ingestion.domain;

public class ReferenceSourceUnavailableException extends RuntimeException {
    public ReferenceSourceUnavailableException(Throwable cause) {
        super("Unable to fetch DataMall reference topology", cause);
    }
}
