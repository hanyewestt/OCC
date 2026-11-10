package com.csd.occ.ingestion.infrastructure.config;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class IdentityProviderConfig {
    @Bean("identityProviderRestClient")
    RestClient identityProviderRestClient(RestClient.Builder builder, IdentityProviderProperties properties) {
        var client = HttpClient.newBuilder().connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(properties.timeout());
        var configured = builder.requestFactory(factory);
        if (properties.enabled()) configured.baseUrl(properties.projectUrl().toString());
        return configured.build();
    }
}
