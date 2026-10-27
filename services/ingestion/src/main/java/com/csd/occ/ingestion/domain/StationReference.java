package com.csd.occ.ingestion.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "station_reference", schema = "ingestion")
public class StationReference {
    @Id private String code;
    private String name;
    // Provider reference files may identify a station without usable coordinates.
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String versionChecksum;
    protected StationReference() {}
    public StationReference(String code, String name, BigDecimal latitude, BigDecimal longitude, String versionChecksum) {
        if (code == null || code.isBlank() || name == null || name.isBlank())
            throw new IllegalArgumentException("Station code and name required");
        new HazardLocation("STATION", code, name, latitude, longitude, null, "STATION_REFERENCE", "PROVIDER_SUPPLIED");
        this.code = code; this.name = name; this.latitude = latitude; this.longitude = longitude; this.versionChecksum = versionChecksum;
    }
    public String code() { return code; }
    public String name() { return name; }
    public BigDecimal latitude() { return latitude; }
    public BigDecimal longitude() { return longitude; }
    public String versionChecksum() { return versionChecksum; }
}
