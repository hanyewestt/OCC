ALTER TABLE disruption.traffic_incident_state ADD COLUMN first_absent_at TIMESTAMPTZ;
ALTER TABLE disruption.traffic_incident_state ADD COLUMN inactive_reason VARCHAR(40);
COMMENT ON COLUMN disruption.traffic_incident_state.first_absent_at IS 'Null until a complete successful scoped poll omits this incident.';
COMMENT ON COLUMN disruption.traffic_incident_state.inactive_reason IS 'Null for active incidents; NO_LONGER_REPORTED does not establish clearance.';
