package com.csd.occ.planning.diagnosis.domain;

import com.csd.occ.planning.recommendation.domain.ControllerBusInstruction;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class EarlyBunchingControllerSuggestionService {
    private final DiagnosisRequestRepository requests;
    private final PlanningScopeService scopes;
    private final SnapshotCodecPort codec;
    private final EarlyBunchingControllerSuggestionPort suggestions;
    private final Clock clock;

    public EarlyBunchingControllerSuggestionService(DiagnosisRequestRepository requests, PlanningScopeService scopes,
            SnapshotCodecPort codec, EarlyBunchingControllerSuggestionPort suggestions, Clock clock) {
        this.requests = requests; this.scopes = scopes; this.codec = codec; this.suggestions = suggestions; this.clock = clock;
    }

    public EarlyBunchingControllerSuggestion suggest(UUID requestId, List<ControllerBusInstruction> instructions,
            String rationale, String actorSubject) {
        var request = requests.findById(requestId).orElseThrow(DiagnosisQueryService.DiagnosisNotFoundException::new);
        var snapshot = decode(request.inputSnapshot());
        if (snapshot == null || !scopes.current().includes(snapshot.event())) {
            throw new SuggestionConflictException("The bunching incident is outside the active data selection.");
        }
        var requested = new EarlyBunchingControllerSuggestion(requestId, instructions, rationale, actorSubject, clock.instant());
        var persisted = suggestions.recordIfAbsent(requested)
                .orElseGet(() -> suggestions.findByRequestId(requestId).orElseThrow());
        if (!persisted.instructions().equals(requested.instructions())
                || !persisted.controllerRationale().equals(requested.controllerRationale())) {
            throw new SuggestionConflictException("A different controller suggestion was already recorded for this incident.");
        }
        return persisted;
    }

    private EvidenceSnapshot decode(String value) {
        if (value == null || value.isBlank()) return null;
        try { return codec.decode(value); } catch (RuntimeException ignored) { return null; }
    }

    public static class SuggestionConflictException extends RuntimeException {
        public SuggestionConflictException(String message) { super(message); }
    }
}
