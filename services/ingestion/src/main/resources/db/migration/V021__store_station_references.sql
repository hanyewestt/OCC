CREATE TABLE ingestion.station_reference (
    code VARCHAR(40) NOT NULL,
    name VARCHAR(250) NOT NULL,
    latitude NUMERIC(10,7),
    longitude NUMERIC(10,7),
    version_checksum VARCHAR(64) NOT NULL,
    CONSTRAINT pk_station_reference PRIMARY KEY (code),
    CONSTRAINT ck_station_reference_coordinates CHECK ((latitude IS NULL AND longitude IS NULL)
        OR (latitude IS NOT NULL AND longitude IS NOT NULL AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180))
);
COMMENT ON COLUMN ingestion.station_reference.latitude IS 'Nullable when provider reference has no station location.';
COMMENT ON COLUMN ingestion.station_reference.longitude IS 'Nullable when provider reference has no station location.';
