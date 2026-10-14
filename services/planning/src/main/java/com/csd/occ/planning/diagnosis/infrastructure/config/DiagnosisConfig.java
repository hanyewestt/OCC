package com.csd.occ.planning.diagnosis.infrastructure.config;

import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import com.csd.occ.planning.diagnosis.infrastructure.http.BoundedResponseBodyInterceptor;
import com.csd.occ.planning.diagnosis.domain.DiagnosisPolicySettings;
import com.csd.occ.planning.infrastructure.security.PeerClientProperties;

@Configuration(proxyBeanMethods = false)
public class DiagnosisConfig {
    @Bean
    Clock diagnosisClock() {
        return Clock.systemUTC();
    }

    @Bean
    DiagnosisPolicySettings diagnosisPolicySettings(DiagnosisProperties properties) {
        return new DiagnosisPolicySettings(properties.evidenceLookback(), properties.evidenceRadiusKm(),
                properties.confidenceThreshold(), properties.maxAttempts(), properties.retryInitialDelay(),
                properties.retryMaxDelay());
    }

    @Bean
    RestClient disruptionFactsRestClient(DiagnosisProperties properties, PeerClientProperties peers) {
        return buildRestClient(properties.disruptionInternalUrl().toString(), properties.requestTimeout(),
                properties.peerResponseMaxBytes(), peers.disruptionToken());
    }

    @Bean
    RestClient ingestionContextRestClient(DiagnosisProperties properties, PeerClientProperties peers) {
        return buildRestClient(properties.ingestionInternalUrl().toString(), properties.requestTimeout(),
                properties.peerResponseMaxBytes(), peers.ingestionToken());
    }

    private RestClient buildRestClient(String baseUrl, java.time.Duration timeout, long responseLimit, String peerToken) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(baseUrl).defaultHeader("X-OCC-Peer-Token", peerToken).requestFactory(requestFactory)
                .requestInterceptor(new BoundedResponseBodyInterceptor(responseLimit)).build();
    }
}
