# Monitoring

The app exposes raw Prometheus-format metrics. The Compose stack does not
include a Prometheus server or Grafana dashboard.

## Local endpoints

| Service | URL |
| --- | --- |
| Ingestion | `http://localhost:6767/actuator/prometheus` |
| Disruption | `http://localhost:6768/actuator/prometheus` |
| Planning | `http://localhost:6769/actuator/prometheus` |
| Gateway | `http://localhost:6770/actuator/prometheus` |

Use the configured host ports if they differ from these defaults. Every metrics
request requires an ADMIN access token in the Authorization bearer header.
Opening the URL directly in the address bar will not attach it. A valid token
with an insufficient role receives 403; missing/invalid authentication receives
401. Keep tokens out of committed files and shared test output.

Nginx returns 404 for `/actuator/**`. Direct backend ports are local development
interfaces; production does not publish them. Production monitoring therefore
needs an explicitly configured private scrape path and credential lifecycle.

## Metrics available

Custom low-cardinality metrics report feed freshness/degradation and arrival
sequence in Ingestion; consumed sequence, deferred work, pending Planning
outbox age, retained attempts, and expired leases in Disruption; and pending
request/job age plus retained model fallback/provider-failure counts in
Planning. Each service also exposes a process-lifetime counter for rows removed
by its scheduled retention cleanup. Standard Spring Boot and Micrometer metrics
include JVM, HTTP server, and database-pool measurements where the service uses
those components. Database gauges query each service's own schema when scraped;
they do not expose event IDs, user IDs, provider prompts, or payloads.

