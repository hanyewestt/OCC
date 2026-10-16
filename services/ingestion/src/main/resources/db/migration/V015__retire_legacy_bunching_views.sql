-- Bunching now consumes the ingestion HTTP contract. These historical
-- cross-schema views and their grants are no longer a supported interface.
DROP VIEW IF EXISTS ingestion.bunching_snapshot;
DROP VIEW IF EXISTS ingestion.bunching_route_direction;
