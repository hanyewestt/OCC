# Per-bus bunching suggestions

Status — all four steps are implemented and verified through the running simulation stack. This plan follows [multi-bus-bunching-design.md](multi-bus-bunching-design.md).

## Completed foundation

- Planning resolves the affected buses in order, shows their prediction gaps, and binds a refreshed review to its source snapshot.
- Controllers can record an outcome for each bus in one decision. Planning validates each action and delivers it to the assigned captain. Instruction history tracks acknowledgement per bus.
- Planning saves per-bus suggestions on new and refreshed reviews when current evidence is available. The review displays and prefills each bus’s outcome, including the reason when no instruction is suggested.

## Implementation plan

1. **Completed: generate one suggested outcome per affected bus.** Use the reviewed group's order and prediction gaps together with fresh per-bus stop and boarding evidence. Apply the existing simulation caps and stop rules. Suggest **No instruction** with a reason when a gap, state, or captain assignment makes an action ineligible. The first bus has no preceding gap in the reviewed group. Planning saves the ordered draft outcomes in the existing recommendation JSON. A refresh can replace a review if the outcomes change while the snapshot stays fresh, such as when a bus starts boarding.
2. **Completed: check the suggestions as a set.** For each consecutive pair, project the gap as `g + b - a`, where `g` is the source gap and `a` and `b` are the preceding and following buses’ timed delays. Require at least the smaller of the original gap and the expected headway. Omit unsafe earlier suggestions after resolving the following buses’ outcomes. Ingestion marks pairs with an intervening bus, and Planning leaves timed suggestions touching those pairs for manual review. Apply the same check to controller edits before issuing. SKIP STOP remains a manual override outside the timed-delay model.
3. **Completed: persist and display the review.** Pending and refresh responses return the saved outcomes for the reviewed snapshot. The Planning page displays them by bus and prefills the action, duration, or no-instruction reason. A refreshed review uses the newly generated set. Single-bus records retain the legacy view. Grouped records without current suggestions ask the controller to refresh. The review shows stored historical confirmation gaps separately from the current prediction gaps.
4. **Completed: reuse decision and delivery.** A refresh saves draft outcomes only. An explicit controller decision uses the existing transaction to revalidate the snapshot, stop state, limits, and current captain assignments before issuing. Grouped HOLD requires progress from the reviewed snapshot. A failed bus validation or delivery rolls back the decision. Identical retries return the existing decision, and captains acknowledge their own instructions separately.

## Short-term recovery timing

- Refreshed simulation suggestions aim for a common 90-second gap, bounded by the recorded expected headway. The expected headway remains the normal-spacing benchmark used by issuance checks.
- For each consecutive run of buses eligible for a timed adjustment, Planning finds the highest feasible common gap up to the recovery target. It walks the run in arrival order, using the preceding bus’s proposed delay and choosing the smallest delay that meets the target. It preserves neighboring gaps and respects the per-bus HOLD and DWELL caps. Unreliable pairs still require manual review.
- Planning generates recovery timing as soon as current group evidence is available during recommendation creation. If that evidence is unavailable, the grouped review asks the controller to refresh. A running Nginx test verified the first pending review already contained 50s and 90s HOLDs and its source snapshot before any refresh. The expected headway is shown separately from the projected gaps beside the reviewed predictions. Through Nginx with a six-minute expected headway, reviewed gaps of 40s and 50s generated HOLDs of 50s and 90s and projected 90s for both gaps. Reviewed gaps of 15s and 15s generated HOLDs of 60s and 120s and projected 75s for both gaps.

## Where to start

- Planning's `BunchingRecommendationPolicy` and `BunchingRecommendationRefreshService` generate the current suggestion. `BunchingInstructionService` validates controller decisions.
- `PendingRecommendationResponse` exposes the review. `initialBusInstructions` in `ControllerPlanning.tsx` prefills the editor.

## Verification

- Through Nginx, a refresh saved four outcomes without issuing any instructions. A controller then saved mixed HOLD and DWELL outcomes. Repeating the decision produced no duplicate deliveries, and two captains independently acknowledged their instructions. Repeating an acknowledgement returned the same receipt.

- Test distinct outcomes in a group of at least four buses, including an approaching bus, a boarding bus, an ineligible bus, and a case where adjacent adjustments interact.
- Test old records, changed membership and order, expired or replaced snapshots, controller edits, missing captain assignments, and atomic rollback.
- Regenerate OpenAPI, run the service and frontend suites, and exercise review, issuance, and separate captain acknowledgements through Nginx. Replay verifies the instruction lifecycle using simulated movement.
