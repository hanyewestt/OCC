package com.csd.occ.ingestion.infrastructure.config;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class DataMallClientConfig {
    @Bean("dataMallRestClient")
    RestClient dataMallRestClient(RestClient.Builder builder, DataMallProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.responseTimeout());
        return builder.baseUrl(properties.baseUrl().toString()).requestFactory(requestFactory).build();
    }
}
