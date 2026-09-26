package com.csd.occ.operations.domain;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrackInferencePolicyTest {
    private final TrackInferencePolicy policy = new TrackInferencePolicy(new TrackInferenceSettings(
            Duration.ofSeconds(180), Duration.ofMinutes(5), Duration.ofHours(24), Duration.ofSeconds(30),
            60,400,25,80,20,Duration.ofSeconds(90),200,20));
    private final Instant time=Instant.parse("2026-09-26T06:00:00Z");
    private final RouteReferencePort.Route route = new RouteReferencePort.Route("190", List.of(
            new RouteReferencePort.Direction("SMRT",1,List.of(stop("A",1,"103.80"),stop("B",2,"103.82")))));
    private RouteReferencePort.Stop stop(String id,int order,String lon) {
        return new RouteReferencePort.Stop(id,order,new BigDecimal("1.35"),new BigDecimal(lon));
    }
    private LiveMapPort.Observation observation(String stop,String lon,Instant at,Integer direction) {
        return new LiveMapPort.Observation(UUID.randomUUID(),stop,"190",direction,1,at.plusSeconds(300),
                new BigDecimal("1.35"),new BigDecimal(lon),at,false);
    }
    @Test void clustersCrossStopEvidenceButNeverTwoArrivalSlotsAtOneStop() {
        var a=policy.project(observation("A","103.805",time,1),route).orElseThrow();
        var b=policy.project(observation("B","103.8051",time,1),route).orElseThrow();
        assertThat(policy.cluster(List.of(a,b))).hasSize(1);
        var otherSlot=policy.project(observation("A","103.805",time,1),route).orElseThrow();
        assertThat(policy.cluster(List.of(a,otherSlot))).hasSize(2);
    }
    @Test void keepsDirectionsAndDistantSourceTimesSeparate() {
        var a=policy.project(observation("A","103.805",time,1),route).orElseThrow();
        var b=policy.project(observation("B","103.805",time.plusSeconds(60),1),route).orElseThrow();
        assertThat(policy.cluster(List.of(a,b))).hasSize(2);
        assertThat(policy.project(observation("A","103.805",time,null),route)).isPresent();
        var bothDirections = new RouteReferencePort.Route("190", List.of(route.directions().getFirst(),
                new RouteReferencePort.Direction("SMRT",2,route.directions().getFirst().stops())));
        assertThat(policy.project(observation("A","103.805",time,null),bothDirections)).isEmpty();
        assertThat(policy.project(observation("A","103.805",time,2),route)).isEmpty();
    }
    @Test void rejectsRepeatedRouteVisitsAndMissingRouteCoordinates() {
        var repeated=new RouteReferencePort.Route("190",List.of(new RouteReferencePort.Direction("SMRT",1,
                List.of(stop("A",1,"103.80"),stop("B",2,"103.82"),stop("A",3,"103.80")))));
        assertThat(policy.project(observation("A","103.805",time,1),repeated)).isEmpty();
        assertThat(policy.project(observation("A","104.5",time,1),route)).isEmpty();
    }
    @Test void acceptsPlausibleForwardMovementRejectsTeleportBackwardsAndEtaSwap() {
        var a=policy.project(observation("A","103.805",time,1),route).orElseThrow();
        var track=new InferredVehicleTrack(a);
        assertThat(policy.matchScore(track,policy.project(observation("A","103.806",time.plusSeconds(60),1),route).orElseThrow())).isPresent();
        assertThat(policy.matchScore(track,policy.project(observation("B","103.819",time.plusSeconds(1),1),route).orElseThrow())).isEmpty();
        assertThat(policy.matchScore(track,policy.project(observation("B","103.801",time.plusSeconds(60),1),route).orElseThrow())).isEmpty();
        assertThat(policy.matchScore(track,policy.project(observation("A","103.805",time.plusSeconds(200),1),route).orElseThrow())).isEmpty();
        track.ambiguous();
        assertThat(policy.matchScore(track,a)).isEmpty();
    }
    @Test void confirmationsRequireNewSourceTimeAndPreserveIdentity() {
        var a=policy.project(observation("A","103.805",time,1),route).orElseThrow();
        var track=new InferredVehicleTrack(a); var id=track.getId();
        track.observe(a); assertThat(track.getConfirmations()).isEqualTo(1);
        track.observe(policy.project(observation("A","103.806",time.plusSeconds(60),1),route).orElseThrow());
        assertThat(track.getId()).isEqualTo(id);
        assertThat(track.getStatus()).isEqualTo("ACTIVE");
        assertThat(track.getConfirmations()).isEqualTo(2);
    }
}
