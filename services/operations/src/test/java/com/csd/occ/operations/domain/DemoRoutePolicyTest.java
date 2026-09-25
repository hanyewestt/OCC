package com.csd.occ.operations.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class DemoRoutePolicyTest {
    @Test
    void checksOnlyTheSelectedDirectionAndRejectsDistantPoints() {
        var route = new RouteReferencePort.Route("190", List.of(
                new RouteReferencePort.Direction("SMRT", 1, List.of(new RouteReferencePort.Stop("44251", 8,
                        new BigDecimal("1.37865"), new BigDecimal("103.75843")))),
                new RouteReferencePort.Direction("SMRT", 2, List.of(new RouteReferencePort.Stop("other", 1,
                        new BigDecimal("1.30000"), new BigDecimal("103.80000"))))));
        var policy = new DemoRoutePolicy(250);

        assertThat(policy.hasDirection(route, 1)).isTrue();
        assertThat(policy.hasDirection(route, 3)).isFalse();
        assertThat(policy.isNearRouteStop(route, 1, new BigDecimal("1.37866"),
                new BigDecimal("103.75844"))).isTrue();
        assertThat(policy.isNearRouteStop(route, 1, new BigDecimal("1.30000"),
                new BigDecimal("103.80000"))).isFalse();
    }
}
