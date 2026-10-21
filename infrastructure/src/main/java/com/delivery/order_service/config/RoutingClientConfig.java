package com.delivery.order_service.config;

import com.delivery.routing.client.HttpRoutingClient;
import com.delivery.routing.client.RoutingClient;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Wires the typed routing boundary while leaving retry/fallback policy to Order. */
@Configuration
public class RoutingClientConfig {

    @Bean
    @Lazy
    RoutingClient routingClient(
            @Value("${routing.service.url:http://routing-service}") String routingServiceUrl,
            @Value("${app.internal.secret:}") String internalSecret,
            @Value("${app.http.connect-timeout-ms:500}") int connectTimeoutMs,
            @Value("${app.http.response-timeout-ms:3000}") int responseTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.max(100, Math.min(connectTimeoutMs, 10_000)));
        requestFactory.setReadTimeout(Math.max(100, Math.min(responseTimeoutMs, 30_000)));
        RestClient restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
        return new HttpRoutingClient(restClient, URI.create(routingServiceUrl), internalSecret);
    }
}
