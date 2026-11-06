package com.csd.occ.disruption.bunching.infrastructure.postgres;

import com.csd.occ.disruption.bunching.domain.BunchingDiagnosisCorrelationPolicy;
import com.csd.occ.disruption.bunching.domain.BunchingDiagnosisLinkPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PostgresBunchingDiagnosisLinkAdapter implements BunchingDiagnosisLinkPort {
    private static final String INSERT_CORRELATION = """
            INSERT INTO disruption.bunching_diagnosis_correlation
                (id, correlation_key, representative_event_id, rule_version, created_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (correlation_key) DO NOTHING
            """;
    private static final String FIND_CORRELATION = """
            SELECT id, representative_event_id
            FROM disruption.bunching_diagnosis_correlation
            WHERE correlation_key = ?
            """;
    private static final String INSERT_LINK = """
            INSERT INTO disruption.bunching_diagnosis_link
                (event_id, correlation_id, canonical_event_id, decision, rule_version, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;
    private static final String FIND_LINK = """
            SELECT canonical_event_id, decision
            FROM disruption.bunching_diagnosis_link
            WHERE event_id = ?
            """;
    private static final String DELETE_ORPHANED_CORRELATIONS = """
            DELETE FROM disruption.bunching_diagnosis_correlation correlation
            WHERE correlation.created_at < ?
              AND NOT EXISTS (
                  SELECT 1 FROM disruption.bunching_diagnosis_link link
                  WHERE link.correlation_id = correlation.id)
            """;

    private final JdbcTemplate jdbc;

    public PostgresBunchingDiagnosisLinkAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Registration register(UUID eventId,
            Optional<BunchingDiagnosisCorrelationPolicy.Correlation> correlation, Instant createdAt) {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(correlation);
        Objects.requireNonNull(createdAt);
        if (correlation.isEmpty()) {
            jdbc.update(INSERT_LINK, eventId, null, eventId, "REPRESENTATIVE",
                    BunchingDiagnosisCorrelationPolicy.RULE_VERSION, Timestamp.from(createdAt));
            return registration(eventId);
        }

        BunchingDiagnosisCorrelationPolicy.Correlation strong = correlation.orElseThrow();
        UUID proposedCorrelationId = UUID.randomUUID();
        jdbc.update(INSERT_CORRELATION, proposedCorrelationId, strong.key(), eventId, strong.ruleVersion(),
                Timestamp.from(createdAt));
        CorrelationRoot root = jdbc.queryForObject(FIND_CORRELATION,
                (result, row) -> new CorrelationRoot(result.getObject("id", UUID.class),
                        result.getObject("representative_event_id", UUID.class)), strong.key());
        if (root == null) throw new IllegalStateException("Diagnosis correlation root was not persisted");
        String decision = root.representativeEventId().equals(eventId)
                ? "REPRESENTATIVE" : "SAME_PAIR_SAME_SWEEP";
        jdbc.update(INSERT_LINK, eventId, root.id(), root.representativeEventId(), decision, strong.ruleVersion(),
                Timestamp.from(createdAt));
        return registration(eventId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long deleteOrphanedBefore(Instant cutoff) {
        Objects.requireNonNull(cutoff);
        return jdbc.update(DELETE_ORPHANED_CORRELATIONS, Timestamp.from(cutoff));
    }

    private Registration registration(UUID eventId) {
        Link link = jdbc.queryForObject(FIND_LINK,
                (result, row) -> new Link(result.getObject("canonical_event_id", UUID.class),
                        result.getString("decision")), eventId);
        if (link == null) throw new IllegalStateException("Diagnosis correlation link was not persisted");
        return new Registration(link.canonicalEventId(), "REPRESENTATIVE".equals(link.decision()));
    }

    private record CorrelationRoot(UUID id, UUID representativeEventId) { }
    private record Link(UUID canonicalEventId, String decision) { }
}
