# Multi-bus bunching workflow: design and assumptions

Status: proposed; implementation and verification are incomplete.

- Scope is incident-level review for all affected buses, including groups larger than three. Derive membership and bus order from incident observations, not the full service fleet; expose unresolved identity or ordering rather than guessing.
- A controller reviews an outcome for every affected bus. An outcome may be an instruction or no instruction needed with a recorded reason. Revalidate membership and relevant state before issuing; require a renewed review when they change.
- Display the observed timing gap for each adjacent bus pair, including missing or uncertain values, and support every affected bus in the recommendation detail and controller override forms.
- In simulation, retain the existing hold and dwell policy as the starting point, validating actions for each bus. Support controller-selected skip-stop instructions for simulation, with a specific, unambiguous upcoming stop and captain assignment; do not assume that simulated stop eligibility proves real-world safety.
- Live instruction issuance remains out of scope and keep-in-view. Existing simulation limits are not approved live operating limits. Live skip-stop eligibility and passenger requirements require operational rules and data before live issuance.
- Track individual instruction delivery and acknowledgement and surface assignment or delivery failures. Preserve transactional consistency and retry behavior.
- Existing API contracts, storage limits, frontend types, simulation behavior, and tests must be updated together. No implementation should be considered complete without automated and running-application verification.
- Confirmed alerts can include observations from multiple snapshots. Resolve and deduplicate by bus identity; retain pair ordering and gaps from the relevant snapshot rather than assuming one observation equals one bus.
- Planning mitigation contexts allow up to 100 distinct affected buses as an internal request bound; this alone does not change the existing three-bus API, persistence, or delivery limits.
- Simulation run alert enrichment resolves affected observations from the confirmed snapshot, orders their vehicles by the saved arrival estimates and returns no affected-vehicle list when required evidence or identities cannot be resolved uniquely. The live-alert response still deduplicates by bus ID and retains all observation IDs. Planning does not yet consume the ordered simulation vehicle list, and missing-identity diagnostics are not yet exposed.
- Disruption snapshot validation accepts up to 100 arrival slots, and the detector evaluates each adjacent gap; the simulation configuration and ingestion database still restrict what can reach that detector.
- Simulation replay, the start-run API, and the form support 2 to 20 buses, matching the existing demo-run database bound. Migration V027 allows 20 simulation arrival slots while retaining the 3-slot observation-only limit. The ingestion OpenAPI contract is generated from the application; the database-backed four-bus replay check covers the migration.
- Recommendation gap display uses the recorded alert headways to include adjacent prediction slots beyond the number of bus references currently supplied by planning. Slot numbers remain observation order, not verified vehicle identities.
- Route-strip gap calculations inspect up to 100 saved prediction slots, retaining the existing three-slot alert table. These slots are not stable vehicle identities.
