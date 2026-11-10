# Service memory sizing - 28 October 2026

The matched synthetic workload reduced median RAM for the four Java containers
from **1,984 MiB to 1,484 MiB (25.2%)**. Including PostgreSQL, the measured total
fell from **2,188 MiB to 1,614 MiB (26.2%)**. Nginx, provider stubs, the load
generator and unrelated projects are excluded from those totals.

> Post-migration note: the Gateway JVM described below has since been removed.
> The current deployment has three application JVMs, and Nginx routes directly to
> Ingestion, Disruption and Planning. The figures below are the earlier
> four-container measurement and are retained as history. A matched whole-stack
> before/after measurement (including swap and Nginx) is still required to state
> this migration's actual saving; do not treat Gateway's earlier 248 MiB median
> as a direct subtraction.

## Static contract footprint - 10 November 2026

The OpenAPI contract and Swagger UI moved out of the runtime: springdoc is a
test-only dependency, the committed contract is packaged as
`/v3/<service>.json`, and Swagger UI is static HTML plus the `swagger-ui`
webjar. A `jattach` native-memory summary showed the runtime had been paying
roughly 130 MB of class metadata and 39-74 MB of code cache per JVM against a
192 MB heap.

Idle `docker compose` stack, same machine, containers up 4-7 minutes; MiB,
Docker working set:

| Container | Before | After | Change |
| --- | ---: | ---: | ---: |
| Ingestion | 544 | 387 | -29% |
| Disruption | 457 | 309 | -32% |
| Planning | 445 | 353 | -21% |
| PostgreSQL | 295 | 146 | -51% |
| Nginx | 26 | 26 | 0% |
| **Total** | **1,767** | **1,221** | **-31%** |

Anonymous memory per JVM fell from 486/418/383 MB to 327/305/296 MB. The
runtime options that changed with it: `-XX:NativeMemoryTracking=summary`
removed, `-XX:TieredStopAtLevel=1` added, `MALLOC_ARENA_MAX=2` added, and
PostgreSQL `shared_buffers` reduced from the image default 128 MB to 64 MB
(`OCC_POSTGRES_SHARED_BUFFERS`). The PostgreSQL figure is the least durable:
it reached 276 MB after a migration run and autovacuum, so treat 146 MB as a
cold-start sample and 276 MB as the settled upper bound.

Limits of that measurement: it is idle, not under load, and it aggregates four
independent changes, so it does not attribute the saving to springdoc alone.
`-XX:TieredStopAtLevel=1` keeps compilation at C1, so peak throughput was not
re-measured and the 120-second k6 profile above should be re-run before relying
on C2-era latency figures. The earlier matched trial measured
`MALLOC_ARENA_MAX=2` at 1,487 MiB against 1,484 MiB without it - no meaningful
saving - so the cap is retained for allocator fragmentation headroom, not as a
measured gain. `shared_buffers=64MB` is a heuristic: `effective_cache_size` and
`work_mem` are unchanged, so planner estimates assume more cache than the server
now keeps.

## Measurement history

Docker working-set memory during the 120-second load run; MiB, rounded. Totals
are medians of simultaneous samples, rather than sums of service medians.

| Container | Baseline median | Tuned median | Baseline peak | Tuned peak |
| --- | ---: | ---: | ---: | ---: |
| Ingestion | 643 | 439 | 645 | 444 |
| Disruption | 484 | 415 | 491 | 420 |
| Planning | 560 | 381 | 572 | 394 |
| Gateway | 297 | 248 | 300 | 261 |
| PostgreSQL | 202 | 129 | 205 | 134 |

The candidate retained default glibc allocator behavior. A separate
`MALLOC_ARENA_MAX=2` trial measured 1,487 MiB across JVMs, versus 1,484 MiB
without the cap. It provided no meaningful additional saving and is not part
of the configuration. The arena control is described in the
[GNU libc manual](https://sourceware.org/glibc/manual/2.36/html_node/Malloc-Tunable-Parameters.html).

## Kept settings

| Setting | Baseline | Tuned |
| --- | --- | --- |
| Maximum Java heap | ~980 MiB per JVM | 192 MiB per business service; 128 MiB Gateway |
| Initial Java heap | ~62 MiB | 32 MiB |
| Collector | G1 | Serial |
| CPUs visible to JVM ergonomics | 22 | 2 |
| Container memory ceiling | Unlimited | 640 MiB Ingestion; 512 MiB Disruption/Planning; 384 MiB Gateway |
| Hikari maximum/minimum idle | 10 / 10 | 4 / 1 |
| Native Memory Tracking | Summary, added for comparison | Summary, enabled in Compose defaults |

The matched benchmark used a 512 MiB ceiling for Ingestion. Its subsequent
normal-stack startup peaked at about 485 MiB in total cgroup memory (including
cache), so the final ceiling is 640 MiB for startup headroom. Its 192 MiB heap
and other JVM settings stay unchanged. Neither run hit a cgroup memory limit;
raising this ceiling is not the source of the working-set reduction.

These are small-service defaults, not measured production capacity limits.
`ActiveProcessorCount` affects JVM ergonomics rather than imposing a Docker CPU
quota. Container ceilings and heap ceilings are limits, not allocated memory.
Under load each tuned database pool grew to four connections, versus ten idle
connections per pool in the baseline.

## Native memory attribution

Committed NMT categories after the workload, MiB. NMT commitments and Docker
working-set RAM measure different things; these columns must not be added to
the container figures above. Class + Metaspace combines the separate NMT
categories for class space and non-class metadata.

| JVM | GC baseline / tuned | Heap baseline / tuned | Class + Metaspace baseline / tuned | Code baseline / tuned |
| --- | ---: | ---: | ---: | ---: |
| Ingestion | 69.1 / 0.4 | 143.0 / 122.7 | 123.7 / 123.4 | 62.1 / 66.4 |
| Disruption | 68.8 / 0.4 | 139.0 / 125.9 | 117.9 / 117.6 | 57.4 / 52.5 |
| Planning | 69.9 / 0.4 | 195.0 / 108.3 | 128.1 / 127.5 | 57.8 / 51.5 |
| Gateway | 67.1 / 0.3 | 74.0 / 71.3 | 63.2 / 63.2 | 35.2 / 36.3 |

GC infrastructure alone fell from about 275 MiB committed across the four JVMs
to about 1.5 MiB. Heap commitment also fell, especially in Planning. Class
metadata stayed nearly unchanged and remains a substantial baseline.
`VM.info` additionally reports free memory retained by the C allocator; those
figures overlap native allocation accounting and are not another total to add.

Detailed per-service NMT commitments, allocator observations, request metrics
and sampling statistics are in [the recorded results](performance-memory-results.json).
For live NMT summaries and baseline/diff commands, see
[service memory and native diagnostics](DEVELOPMENT.md#service-memory-and-native-diagnostics).

## Workload and limits

Baseline images were built from `cc6d994`, after Prometheus removal. The
candidate used the same application code plus the Hikari defaults in this
change. Both used the pinned Java 25 Temurin runtime, the same 3.822 GiB Docker
engine and NMT summary. The baseline override removed container limits and set
only `JAVA_TOOL_OPTIONS=-XX:NativeMemoryTracking=summary`. The kept candidate
used the Compose and `.env.example` defaults in this change, except for the
subsequent increase in Ingestion's container ceiling described above. No forced GC or
allocator trim was used.

Each measured run used newly started JVMs, the same synthetic reference fixtures
and its own newly generated demo run. The baseline JVMs were recreated after an
initial test-only startup race between seeding and a one-second poll; the
measured run used the normal 60-second arrival initial delay and zero restarts.

The isolated `occ-memory-benchmark` stack reused `infra/compose.test.yaml`,
`infra/compose.workflow-test.yaml`, `tests/system/provider_stubs.conf` and the
ephemeral issuer generated by `create_es256_auth_fixtures.mjs`. Provider responses
and credentials were synthetic; the development database was not used. The
baseline and candidates were measured separately, without Maven or Docker builds
running during load. Docker stats was sampled with a five-second wait between
calls; the actual intervals are recorded in the JSON.

`demo_workflow_smoke.py` verified simulation through Disruption and Planning,
and saved the IDs supplied to `important_api_performance.js`. The pinned k6
2.3.0 profile used four VUs for 120 seconds across ten authenticated endpoints,
including arrivals, route/stop reads, disruption alerts and recommendations.
Its think time limits request rate; this is not a maximum-throughput test.

| Check | Baseline | Tuned |
| --- | ---: | ---: |
| Requests | 2,040 | 2,040 |
| Request failures | 0 | 0 |
| Overall request p95 | 30.9 ms | 31.5 ms |
| Slowest endpoint p95 | 42.7 ms | 49.4 ms |

Every endpoint passed the existing p95 < 2,000 ms threshold and response-shape
checks. The separate live-traffic workflow also verified raw/normalized storage
and a completed Planning request using the synthetic provider. The final trial
had zero container restarts, no cgroup OOM events and only 8 KiB of observed
swap in Gateway; that cannot explain the measured reduction.

This is one short run per configuration against a small reference fixture. It
does not establish long-term leak freedom, high-concurrency capacity, full
national-topology import headroom, or a statistically significant latency change.
The normal development stack is checked separately after applying the defaults.

## Automated and normal-stack verification

`mvnw clean verify -Docc.exportOpenapi=true` passed all 589 tests, with zero
failures, errors or skips. OpenAPI contracts were regenerated and unchanged.
Local and production Compose examples validate. A separate read-only review
found no actionable issues.

The normal development stack was rebuilt/recreated with its existing database.
All four readiness endpoints passed; Nginx served the frontend, frontend config
and public notices. Protected APIs returned 401 without a token; the edge kept
management endpoints hidden. All four services were healthy without restarts
or OOM kills. Live polling recorded 354 successful bus-arrival polls in the
checked window, plus successful flood, rail and traffic polls. Roadworks remained
partial with HTTP 200 and `incomplete_feed`, matching earlier records; this is
provider completeness, not proof that every live feed is complete.

The documented matching-JDK helper successfully collected NMT summaries and
`baseline` / `summary.diff` from the normal ingestion process during live polling.
The JSON also records a normal-stack RAM snapshot. That snapshot has different
data, startup ages and workload from the synthetic pair, so it is not another
controlled before/after result. The isolated benchmark stack and its disposable
database volume were removed; the normal development app remains running.

## EC2 CD capacity qualification - pending

The EC2 CD deployment targets a t3.small (2 GiB) host with the tuning above
retained. The 1,221 MiB idle sample is a Docker working-set measurement on a
3.822 GiB engine — it is **not** capacity evidence for the 2 GiB host:
PostgreSQL settled at 276 MiB after a migration run and autovacuum, the sample
excluded host overhead, and no loaded run has executed on the target size.
The required measurement (cold/warm start, full enabled reference
import/polling, setup with app writers stopped, image pull/extract,
backup/autovacuum/restart, representative authenticated and SSE load,
MemAvailable, cgroup memory.events/OOM, swap growth, restarts, latency and
standard-credit depletion, zero OOM/unexpected restarts) is an authorized-rollout
check recorded in the [EC2 CD verification record](superpowers/reviews/2026-11-10-ec2-cd-verification.md);
no capacity claim is made until it runs.
