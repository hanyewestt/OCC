# Hybrid fleet map and controlled bunching demo

## Decision and scope

OCC will show available DataMall bus-arrival observations on a map and allow an
admin or controller to start a controlled simulation using an explicitly captured
observation snapshot plus injected demo vehicles. The goal is a credible demo with
stable vehicles and drivers for simulated directives, without claiming that
DataMall identifies or tracks the operator's fleet. Ingestion now exposes a
read-only `/api/v1/map/observed-buses` feed of usable, unverified positions.
Operations now stores draft demo runs, vehicles, drivers, and assignments behind
role-protected endpoints and checks service, direction, and proximity to an
imported route stop. A simple draft map shows the imported stop sequence and
prototype-owned vehicles without live buses. Track inference, observation
capture, driven-path validation, simulation execution, a combined live/demo map,
and driver delivery remain planned work.

Bus Arrival v3 is scoped to polled stops and the next approaching buses. Its
latitude, longitude, ETA, service, and arrival slot can change or be absent. A slot
number, coordinate, `VisitNumber`, and order among buses are not a vehicle ID or
plate. Coverage is limited to configured polling targets, not the full fleet.

## Separate records and ownership

| Record | Meaning | Owner and classification |
| --- | --- | --- |
| Raw and normalized arrival | What DataMall returned for one stop/service poll, with provenance and freshness | Ingestion; `OBSERVATION_ONLY` |
| Inferred track | A temporary hypothesis linking compatible observations across polls | A read model for the live map; `OBSERVATION_ONLY`, with confidence, status, and evidence IDs |
| Demo vehicle | A prototype-owned bus with a stable internal ID and clearly synthetic display label or plate | Operations simulation; `SIMULATION` |
| Demo assignment | A stable link between a demo vehicle and demo driver within a service/direction run; trip linkage is planned | Operations simulation; `SIMULATION` |
| Directive | A validated action addressed to a demo assignment and its driver | Operations simulation; `SIMULATION` |

Do not place a fabricated plate on a DataMall observation or inferred track. Store
synthetic plates only as labelled demo data; use immutable internal IDs as keys.
The map must show source type, fetch time, freshness, and identity uncertainty.
Observed markers and demo markers must remain visually distinct. Do not imply that
every observed marker is a unique bus or that the map covers every active bus.
Plot a live bus marker only when the response has a usable, source-monitored
coordinate. An ETA without a usable coordinate belongs in the stop arrival list,
not at an invented map position.

## Inferred tracks and duplicate handling

A future tracker may match observations using service, mapped direction, route
progress, time, ETA, position, and physically possible movement. It must retain
the contributing observation IDs, confidence, last-seen time, and status. Match
only when evidence supports a unique candidate. If vehicles overtake, disappear,
or two matches are plausible, lower confidence or mark the track `AMBIGUOUS`;
expire it after a configurable interval. Never swap a plate or silently reassign
an identity because arrival order changed. Coordinates alone are insufficient for
deduplication. An inferred track cannot receive a directive.

The raw and normalized ingestion records remain the source of truth for what was
received. Track matching is a rebuildable projection, not a rewrite of those
records. Avoid duplicate map markers only where the evidence supports a match;
otherwise show uncertainty instead of inventing certainty.

## Controlled scenario flow

1. A controller explicitly starts a demo run and selects a route, direction, and
   observation time. Capture the selected live observations, topology version,
   freshness, and source IDs into an immutable, versioned run input. The capture is
   a simulation baseline, not a continuing live feed. Reject stale or ambiguous
   direction evidence instead of silently using it.
2. Add one or more demo vehicles at valid locations on that route, with explicit
   simulated ETAs and stable demo assignments to demo drivers. Injection never
   writes into `ingestion.raw_observation`, `bus_arrival_observation`, or the live
   bunching feed. The map labels the combined view as a simulation overlay.
3. Advance the run through deterministic time steps. Compute bunching from the
   run's simulated arrival sequence and record exactly which observed baseline
   and injected vehicles contributed to each headway. Keep the current live
   detector and its alerts observation-only. The simulation's confirmation rule
   and evidence are versioned separately from the live detector's rule. A single
   captured poll cannot be described as two consecutive live confirmations;
   subsequent demo steps are explicitly simulated.
4. A controller may approve a simulated mitigation only when the intended bus is
   an unambiguous demo assignment, the direction and control stop are valid, and
   the ordinary action validators pass. If the needed action would target a live
   observed or inferred bus, show the problem but disable execution.
5. Deliver the directive to the assigned demo driver's interface and record
   acknowledgement against the directive and assignment IDs. Display simulated
   outcomes separately from live DataMall observations.

This explicit capture is the only path by which live observations can inform a
demo run. Later DataMall polls do not mutate a run or trigger its planning. Replays
use the pinned input, not whatever is currently in the short-lived telemetry
tables. No action is sent to a real operator or bus.

## Implementation work

- Add a versioned simulation input and explicit capture API with authorization,
  freshness checks, provenance, and replay support. Keep it separate from the
  existing ingestion API and live alert state.
- Build a map read model for current observations. Add inferred tracks only with
  confidence, ambiguity, expiry, and duplicate-prevention tests.
- Add protected demo vehicle placement and assignment APIs, persistence, and UI.
  Validate service, direction, route location, driver assignment, and run scope.
- Feed injected vehicle timings to the simulation detector, preserving the source
  of every input. Cover overtaking, missing coordinates, repeated stop visits,
  stale observations, ambiguous direction, and two visually overlapping buses.
- Connect a confirmed simulated event through controller approval, directive
  validation, demo driver delivery, and idempotent acknowledgement.
- Add clear UI labels and tests proving that a live-only alert cannot create a
  directive, injected records cannot appear as live telemetry, and a directive
  cannot target an inferred track.

Any new settings follow the repository's environment-variable and typed-property
rules. Any new or changed API updates its OpenAPI contract.
