package com.csd.occ.bunching.infrastructure.config;

import com.csd.occ.bunching.domain.BunchingPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.context.annotation.Primary;

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
    RestClient ingestionRestClient(BunchingProperties settings) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(settings.ingestionConnectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(settings.ingestionResponseTimeout());
        return RestClient.builder().baseUrl(settings.ingestionBaseUrl().toString()).requestFactory(factory).build();
    }

    @Bean
    RestClient diagnosisRestClient(DiagnosisOutboxProperties settings) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(settings.requestTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(settings.requestTimeout());
        return RestClient.builder().baseUrl(settings.internalUrl().toString()).requestFactory(factory).build();
    }
}
