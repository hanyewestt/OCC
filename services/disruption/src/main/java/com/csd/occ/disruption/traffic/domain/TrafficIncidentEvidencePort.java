package com.csd.occ.disruption.traffic.domain;

public interface TrafficIncidentEvidencePort {
    String encode(TrafficIncidentPollPort.TrafficIncidentPoll poll, TrafficIncidentSnapshot incident, String action);
    TrafficIncidentSnapshot decode(String evidence);
}
