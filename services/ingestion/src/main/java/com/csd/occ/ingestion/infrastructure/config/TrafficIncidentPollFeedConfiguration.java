package com.csd.occ.ingestion.infrastructure.config;

import com.csd.occ.ingestion.domain.PollRunRepository;
import com.csd.occ.ingestion.domain.TrafficIncidentObservationRepository;
import com.csd.occ.ingestion.domain.TrafficIncidentPollFeedService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TrafficIncidentPollFeedProperties.class)
public class TrafficIncidentPollFeedConfiguration {
    @Bean
    TrafficIncidentPollFeedService trafficIncidentPollFeedService(PollRunRepository pollRuns,
            TrafficIncidentObservationRepository incidents, TrafficIncidentPollFeedProperties properties) {
        return new TrafficIncidentPollFeedService(pollRuns, incidents,
                properties.defaultPageSize(), properties.maxPageSize());
    }
}
