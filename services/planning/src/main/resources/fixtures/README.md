# Recommendation fixture provenance

`bunching-mitigation-context.json` aligns its Route 190 identifiers and first
recommended action with the repository's shared simulation in
`SimulatedData/route190/bunching/scenario.json`:

- monitored stop `44251`
- follower `SBS5898U`
- approved control stop `44251`
- first hold of 120 seconds

The fixture's `221`-second headway before the action is the shared scenario's
latest confirming observation. The `341`-second headway after the action is a
deterministic single-action estimate (`221 + 120`), not an observed outcome.

The queue and passenger-minute values remain synthetic single-action demo
estimates because the shared simulation does not publish queue metrics or
attribute its full multi-hold passenger-wait result to the first hold alone.
They must not be presented as measurements from the shared simulation.
