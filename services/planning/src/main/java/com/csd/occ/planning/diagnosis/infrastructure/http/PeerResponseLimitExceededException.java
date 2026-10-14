package com.csd.occ.planning.diagnosis.infrastructure.http;

import java.io.IOException;

public final class PeerResponseLimitExceededException extends IOException {
    private static final long serialVersionUID = 1L;

    public PeerResponseLimitExceededException(long limit) {
        super("Peer response exceeds " + limit + " bytes");
    }
}
