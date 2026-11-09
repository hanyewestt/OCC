CREATE TABLE disruption.bunching_diagnosis_correlation (
    id UUID NOT NULL,
    correlation_key CHAR(64) NOT NULL,
    representative_event_id UUID NOT NULL,
    rule_version VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_bunching_diagnosis_correlation PRIMARY KEY (id),
    CONSTRAINT uq_bunching_diagnosis_correlation_key UNIQUE (correlation_key),
    CONSTRAINT ck_bunching_diagnosis_correlation_key CHECK (
        correlation_key ~ '^[0-9a-f]{64}$')
);

COMMENT ON COLUMN disruption.bunching_diagnosis_correlation.representative_event_id IS
    'Immutable originating event identity; intentionally not a foreign key so the correlation survives independent event retention while members remain';

CREATE TABLE disruption.bunching_diagnosis_link (
    event_id UUID NOT NULL,
    correlation_id UUID,
    canonical_event_id UUID NOT NULL,
    decision VARCHAR(30) NOT NULL,
    rule_version VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_bunching_diagnosis_link PRIMARY KEY (event_id),
    CONSTRAINT fk_bunching_diagnosis_link_event FOREIGN KEY (event_id)
        REFERENCES disruption.disruption_event (id) ON DELETE CASCADE,
    CONSTRAINT fk_bunching_diagnosis_link_correlation FOREIGN KEY (correlation_id)
        REFERENCES disruption.bunching_diagnosis_correlation (id) ON DELETE CASCADE,
    CONSTRAINT ck_bunching_diagnosis_link_decision CHECK (
        decision IN ('REPRESENTATIVE', 'SAME_PAIR_SAME_SWEEP')),
    CONSTRAINT ck_bunching_diagnosis_link_representative CHECK (
        (decision = 'REPRESENTATIVE' AND event_id = canonical_event_id)
        OR (decision = 'SAME_PAIR_SAME_SWEEP' AND event_id <> canonical_event_id)),
    CONSTRAINT ck_bunching_diagnosis_link_member_correlation CHECK (
        decision = 'REPRESENTATIVE' OR correlation_id IS NOT NULL)
);

-- A strong correlation has one representative membership even under concurrent service instances.
CREATE UNIQUE INDEX uq_bunching_diagnosis_link_representative_correlation
    ON disruption.bunching_diagnosis_link (correlation_id)
    WHERE decision = 'REPRESENTATIVE' AND correlation_id IS NOT NULL;
CREATE INDEX ix_bunching_diagnosis_link_canonical
    ON disruption.bunching_diagnosis_link (canonical_event_id);
CREATE INDEX ix_bunching_diagnosis_link_correlation
    ON disruption.bunching_diagnosis_link (correlation_id);
