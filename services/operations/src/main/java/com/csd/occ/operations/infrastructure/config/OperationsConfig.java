package com.csd.occ.operations.infrastructure.config;

import com.csd.occ.operations.domain.DemoFleetPagePolicy;
import com.csd.occ.operations.domain.DemoRoutePolicy;
import java.time.Clock;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class OperationsConfig {
    @Bean
    Clock clock() { return Clock.systemUTC(); }

    @Bean
    DemoFleetPagePolicy demoFleetPagePolicy(DemoFleetProperties properties) {
        return new DemoFleetPagePolicy(properties.defaultPageSize(), properties.maxPageSize());
    }

    @Bean
    DemoRoutePolicy demoRoutePolicy(DemoRouteProperties properties) {
        return new DemoRoutePolicy(properties.maxStopDistanceMeters());
    }

    @Bean
    RestClient ingestionTopologyClient(IngestionTopologyProperties properties) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());
        return RestClient.builder().baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory).build();
    }
}
