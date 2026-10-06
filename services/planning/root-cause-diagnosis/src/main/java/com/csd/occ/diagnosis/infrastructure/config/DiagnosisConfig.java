package com.csd.occ.diagnosis.infrastructure.config;

import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import com.csd.occ.diagnosis.infrastructure.http.BoundedResponseBodyInterceptor;
import com.csd.occ.diagnosis.domain.DiagnosisPolicySettings;

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
    RestClient bunchingFactsRestClient(DiagnosisProperties properties) {
        return restClient(properties.bunchingInternalUrl().toString(), properties.requestTimeout(), properties.peerResponseMaxBytes());
    }

    @Bean
    RestClient ingestionContextRestClient(DiagnosisProperties properties) {
        return restClient(properties.ingestionInternalUrl().toString(), properties.requestTimeout(), properties.peerResponseMaxBytes());
    }

    @Bean
    RestClient recommendationRestClient(DiagnosisProperties properties) {
        return restClient(properties.recommendationInternalUrl().toString(), properties.requestTimeout(), properties.peerResponseMaxBytes());
    }

    private RestClient restClient(String baseUrl, java.time.Duration timeout, long responseLimit) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory)
                .requestInterceptor(new BoundedResponseBodyInterceptor(responseLimit)).build();
    }
}
