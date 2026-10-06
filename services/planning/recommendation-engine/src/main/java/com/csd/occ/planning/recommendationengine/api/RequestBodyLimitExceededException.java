package com.csd.occ.planning.recommendationengine.api;

import java.io.IOException;

public final class RequestBodyLimitExceededException extends IOException {
    private static final long serialVersionUID = 1L;

    public RequestBodyLimitExceededException(long limit) {
        super("Request body exceeds " + limit + " bytes");
    }
}
