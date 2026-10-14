package com.csd.occ.disruption.bunching.infrastructure.config;

import com.csd.occ.disruption.bunching.domain.BunchingPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.context.annotation.Primary;
import com.csd.occ.disruption.infrastructure.security.PeerClientProperties;

@Configuration
public class BunchingConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    BunchingPolicy bunchingPolicy() {
        return new BunchingPolicy();
    }

    @Bean
    @Primary
    RestClient ingestionRestClient(BunchingProperties settings, PeerClientProperties peers) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(settings.ingestionConnectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(settings.ingestionResponseTimeout());
        return RestClient.builder().baseUrl(settings.ingestionBaseUrl().toString())
                .defaultHeader("X-OCC-Peer-Token", peers.ingestionToken()).requestFactory(factory).build();
    }

    @Bean
    RestClient diagnosisRestClient(DiagnosisOutboxProperties settings, PeerClientProperties peers) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(settings.requestTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(settings.requestTimeout());
        return RestClient.builder().baseUrl(settings.internalUrl().toString())
                .defaultHeader("X-OCC-Peer-Token", peers.planningToken()).requestFactory(factory).build();
    }
}
