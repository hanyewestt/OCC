package com.csd.occ.diagnosis.domain;

import java.util.List;

public interface RootCauseModelPort {
    ModelCallResult diagnose(EvidenceSnapshot snapshot, List<String> validationFeedback, int attemptNumber);

    record ModelCallResult(ModelOutput output, ModelInvocationDetails invocation) {
    }
}
