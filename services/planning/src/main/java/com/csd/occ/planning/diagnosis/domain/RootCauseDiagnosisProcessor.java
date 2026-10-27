package com.csd.occ.planning.diagnosis.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;

@Service
public class RootCauseDiagnosisProcessor {
    private final DiagnosisRequestRepository requests;
    private final EventFactsPort eventFacts;
    private final ContextEvidencePort evidenceContext;
    private final RootCauseModelPort model;
    private final SnapshotCodecPort snapshots;
    private final RecommendationPayloadCodecPort payloads;
    private final DiagnosisCompletionService completion;
    private final BunchingEvidenceSupportPolicy associations = new BunchingEvidenceSupportPolicy();
    private final DiagnosisSummaryPolicy summaries = new DiagnosisSummaryPolicy();
    private final RetryBackoffPolicy retryBackoff = new RetryBackoffPolicy();
    private final DiagnosisPolicySettings settings;
    private final Clock clock;

    public RootCauseDiagnosisProcessor(DiagnosisRequestRepository requests, EventFactsPort eventFacts,
            ContextEvidencePort evidenceContext, RootCauseModelPort model, SnapshotCodecPort snapshots,
            RecommendationPayloadCodecPort payloads, DiagnosisCompletionService completion,
            DiagnosisPolicySettings settings, Clock clock) {
        this.requests = requests; this.eventFacts = eventFacts; this.evidenceContext = evidenceContext;
        this.model = model; this.snapshots = snapshots; this.payloads = payloads;
        this.completion = completion; this.settings = settings; this.clock = clock;
    }

    public void process(UUID requestId) {
        DiagnosisRequest request = requests.findById(requestId).orElseThrow();
        List<ModelInvocationDetails> invocationDetails = new ArrayList<>();
        String snapshotHash = null;
        try {
            EvidenceSnapshot retained = request.inputSnapshot() == null ? null : snapshots.decode(request.inputSnapshot());
            BunchingEventFacts event = retained == null ? eventFacts.findConfirmedEvent(request.eventId()) : retained.event();
            if (!event.evidenceIds().stream().allMatch(id -> id != null && !id.isBlank())
                    || event.confirmingObservationTimes().size() < 2
                    || event.confirmingObservationIds().stream().filter(java.util.Objects::nonNull).distinct().count() < 2)
                throw new DiagnosisFailureException("INCOMPLETE_EVENT_FACTS", false);
            RootCauseContext context = bound(retained == null ? evidenceContext.findContext(event) : retained.context(), event);
            if (!PlanningScope.valid(event.classification(), event.demoRunId())
                    || !java.util.Objects.equals(event.demoRunId(), context.demoRunId())
                    || !event.classification().equals(context.classification()))
                throw new DiagnosisFailureException("EVIDENCE_PROVENANCE_MISMATCH", false);
            Instant latestConfirmationAt = event.confirmingObservationTimes().getLast();
            if (context.arrivals().stream().anyMatch(arrival -> !event.classification().equals(arrival.classification())
                    || !java.util.Objects.equals(event.demoRunId(), arrival.demoRunId())
                    || !event.serviceId().equals(arrival.serviceId())
                    || !event.monitoredStopCode().equals(arrival.stopCode())
                    || !java.util.Objects.equals(event.directionId(), arrival.directionId())
                    || arrival.fetchedAt() == null || arrival.fetchedAt().isAfter(latestConfirmationAt))
                    || context.incidents().stream().anyMatch(incident -> !event.classification().equals(incident.classification())
                    || !java.util.Objects.equals(event.demoRunId(), incident.demoRunId()) || incident.fetchedAt() == null
                    || incident.fetchedAt().isAfter(event.firstConfirmedAt())
                    || incident.observedAt() != null && incident.observedAt().isAfter(event.firstConfirmedAt())))
                throw new DiagnosisFailureException("EVIDENCE_PROVENANCE_MISMATCH", false);
            List<UUID> eventObservationIds = event.confirmingObservationIds().stream().distinct().sorted().toList();
            List<UUID> contextObservationIds = context.arrivals().stream().map(ArrivalEvidence::observationId)
                    .distinct().sorted().toList();
            if (!eventObservationIds.equals(contextObservationIds))
                throw new DiagnosisFailureException("EVIDENCE_OBSERVATION_SET_MISMATCH", false);
            List<UUID> referenceVersions = context.arrivals().stream().map(ArrivalEvidence::referenceVersionId)
                    .filter(java.util.Objects::nonNull).distinct().toList();
            if (referenceVersions.size() > 1 || context.referenceVersionId() != null
                    && referenceVersions.stream().anyMatch(reference -> !context.referenceVersionId().equals(reference)))
                throw new DiagnosisFailureException("EVIDENCE_REFERENCE_VERSION_MISMATCH", false);
            List<String> allowedEvidenceIds = java.util.stream.Stream.of(event.evidenceIds().stream(),
                    context.arrivals().stream().map(ArrivalEvidence::evidenceId),
                    context.incidents().stream().map(IncidentEvidence::evidenceId))
                    .flatMap(stream -> stream).filter(id -> id != null && !id.isBlank()).distinct().toList();
            EvidenceSnapshot snapshot = new EvidenceSnapshot(event, context, allowedEvidenceIds, event.classification());
            String snapshotJson = snapshots.encode(snapshot);
            snapshotHash = hash(snapshotJson);

            List<String> feedback = List.of();
            ModelOutput output = null;
            BunchingEvidenceSupportPolicy.AssociatedEvidence association = null;
            for (int attempt = 1; attempt <= 2; attempt++) {
                RootCauseModelPort.ModelCallResult call;
                try {
                    call = model.diagnose(snapshot, feedback, attempt);
                } catch (ModelInvocationFailureException providerFailure) {
                    invocationDetails.add(providerFailure.invocationDetails());
                    if (providerFailure.invocationDetails().generatedResponse() != null && attempt == 1) {
                        feedback = List.of("Return valid JSON matching the requested schema.");
                        continue;
                    }
                    throw new DiagnosisFailureException("MODEL_PROVIDER_FAILURE", providerFailure.isRetryable(), providerFailure);
                }
                try {
                    output = call.output();
                    validateOutput(output, snapshot);
                    association = associations.associate(event, context, output, settings.evidenceLookback(),
                            settings.evidenceRadiusKm());
                    invocationDetails.add(withOutcome(call.invocation(), "VALID"));
                    break;
                } catch (RuntimeException invalid) {
                    invocationDetails.add(withOutcome(call.invocation(), "INVALID"));
                    feedback = List.of(safeValidationMessage(invalid));
                    if (attempt == 2) throw new DiagnosisFailureException("MODEL_OUTPUT_INVALID", false, invalid);
                }
            }
            if (output == null || association == null) throw new DiagnosisFailureException("MODEL_OUTPUT_INVALID", false);
            List<String> limitations = limitations(output, context, association);
            String likelyCause = summaries.summarize(association.causeCode(), association.causeSubtype(),
                    association.evidenceIds(), snapshot).likelyCause();
            UUID diagnosisId = UUID.randomUUID();
            RootCauseDiagnosis diagnosis = new RootCauseDiagnosis(diagnosisId, request.id(), association.causeCode(),
                    association.causeSubtype(), likelyCause, null, null, association.evidenceIds(),
                    limitations, null, snapshotJson, snapshotHash, "GOOGLE_GENAI",
                    invocationDetails.getLast().model(), invocationDetails.getLast().promptVersion(),
                    invocationDetails.getLast().schemaVersion(), clock.instant());
            if ("OBSERVATION_ONLY".equals(event.classification())) {
                completion.completeAdvisory(request.id(), diagnosis, invocationDetails, snapshotHash);
                return;
            }
            UUID deliveryId = UUID.randomUUID();
            String payload = payloads.encode(deliveryId, event, diagnosis, event.classification());
            completion.complete(request.id(), diagnosis, invocationDetails, snapshotHash, payload, deliveryId);
        } catch (DiagnosisFailureException failure) {
            fail(request, failure.failureCode(), failure.isRetryable(), invocationDetails, snapshotHash);
        } catch (EvidenceLimitExceededException failure) {
            fail(request, "EVIDENCE_LIMIT_EXCEEDED", false, invocationDetails, snapshotHash);
        } catch (RuntimeException failure) {
            fail(request, "DIAGNOSIS_PROCESSING_FAILURE", true, invocationDetails, snapshotHash);
        }
    }

    private RootCauseContext bound(RootCauseContext context, BunchingEventFacts event) {
        if (context.arrivals().size() > 20 || context.incidents().size() > 50)
            throw new DiagnosisFailureException("EVIDENCE_LIMIT_EXCEEDED", false);
        Instant cutoff = event.firstConfirmedAt().minus(settings.evidenceLookback());
        List<IncidentEvidence> incidents = context.incidents().stream().map(incident -> new IncidentEvidence(
                incident.evidenceId(), incident.incidentId(), incident.incidentType(), incident.message(),
                incident.latitude(), incident.longitude(), incident.observedAt(), incident.fetchedAt(),
                incident.classification(), incident.demoRunId()))
                .filter(incident -> {
                    Instant relevantAt = incident.observedAt() == null ? incident.fetchedAt() : incident.observedAt();
                    return incident.fetchedAt() != null && !incident.fetchedAt().isAfter(event.firstConfirmedAt())
                            && relevantAt != null && !relevantAt.isBefore(cutoff)
                            && !relevantAt.isAfter(event.firstConfirmedAt());
                })
                .sorted(java.util.Comparator.comparing(incident -> incident.observedAt() == null
                        ? incident.fetchedAt() : incident.observedAt()))
                .toList();
        List<String> missingFields = new ArrayList<>(context.missingFields());
        if (incidents.isEmpty() && !missingFields.contains("traffic-incidents-within-lookback"))
            missingFields.add("traffic-incidents-within-lookback");
        List<String> distinctMissingFields = missingFields.stream().distinct().toList();
        if (distinctMissingFields.size() > 20)
            throw new DiagnosisFailureException("EVIDENCE_LIMIT_EXCEEDED", false);
        return new RootCauseContext(context.classification(), context.demoRunId(), context.referenceVersionId(),
                context.arrivals(), incidents, distinctMissingFields);
    }

    private void validateOutput(ModelOutput output, EvidenceSnapshot snapshot) {
        if (output == null || output.causeCode() == null) throw new IllegalArgumentException("causeCode is required");
        if (!List.of("TRAFFIC_INCIDENT", "UNKNOWN").contains(output.causeCode()))
            throw new IllegalArgumentException("causeCode is unsupported");
        if (output.supportingEvidenceIds().size() > 100 || output.limitations().size() > 20
                || output.supportingEvidenceIds().stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 200))
            throw new IllegalArgumentException("model output exceeded configured schema limits");
        if (output.supportingEvidenceIds().stream().anyMatch(id -> !snapshot.allowedEvidenceIds().contains(id)))
            throw new IllegalArgumentException("model cited evidence outside the supplied allow-list");
        if (output.limitations().stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 500))
            throw new IllegalArgumentException("limitations must be non-empty strings of at most 500 characters");
        if ("TRAFFIC_INCIDENT".equals(output.causeCode())
                && (output.likelyCause() == null || output.likelyCause().isBlank() || output.likelyCause().length() > 1_000))
            throw new IllegalArgumentException("likelyCause is required and limited to 1000 characters");
        if (output.causeSubtype() != null && output.causeSubtype().length() > 100)
            throw new IllegalArgumentException("causeSubtype is limited to 100 characters");
    }

    private List<String> limitations(ModelOutput output, RootCauseContext context, BunchingEvidenceSupportPolicy.AssociatedEvidence association) {
        List<String> values = context.missingFields().stream().map(field -> "Missing evidence field: " + field)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        values.addAll(association.limitations());
        if ("OBSERVATION_ONLY".equals(context.classification()))
            values.add("Live diagnosis only: validated vehicle assignments, control stops and crowd-safety inputs are unavailable; no operational instruction is issued.");
        if ("UNKNOWN".equals(output.causeCode())) values.add("No contextual evidence supported a specific cause.");
        values.addAll(output.limitations());
        return values.stream().filter(value -> value != null && !value.isBlank()).distinct().limit(20).toList();
    }

    private ModelInvocationDetails withOutcome(ModelInvocationDetails details, String outcome) {
        return new ModelInvocationDetails(details.provider(), details.model(), details.responseId(),
                details.promptVersion(), details.schemaVersion(), details.startedAt(), details.completedAt(),
                details.latencyMillis(), details.promptTokens(), details.completionTokens(), details.totalTokens(),
                outcome, details.failureCategory(), details.attemptNumber(), details.inputSnapshot(), details.generatedResponse());
    }

    private String safeValidationMessage(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null ? "Return output matching the required schema and evidence constraints."
                : truncate(message, 300);
    }

    private String truncate(String value, int limit) {
        if (value == null) return null;
        if (value.length() <= limit) return value;
        int end = Character.isHighSurrogate(value.charAt(limit - 1)) ? limit - 1 : limit;
        return value.substring(0, end).stripTrailing();
    }

    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException("SHA-256 is unavailable", failure); }
    }

    private void fail(DiagnosisRequest request, String code, boolean retryable,
                      List<ModelInvocationDetails> invocationDetails, String snapshotHash) {
        int usedAttempts = request.attemptCount();
        boolean attemptsRemaining = usedAttempts < settings.maxAttempts();
        double jitter = ThreadLocalRandom.current().nextDouble(-0.20, 0.2000001);
        Instant retryAt = clock.instant().plus(retryBackoff.delay(settings.retryInitialDelay(),
                settings.retryMaxDelay(), Math.max(1, usedAttempts), jitter));
        completion.fail(request.id(), code, retryable, attemptsRemaining, retryAt, invocationDetails, snapshotHash);
    }
}
