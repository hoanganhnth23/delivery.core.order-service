package com.delivery.order_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import io.netty.channel.ChannelOption;

import java.time.Duration;

/**
 * Configuration cho WebClient và RestTemplate để gọi các external services
 */
@Configuration
public class WebClientConfig {
    
    @Bean(destroyMethod = "dispose")
    public ConnectionProvider orderHttpConnectionProvider(
            @Value("${app.http.max-connections:32}") int maxConnections,
            @Value("${app.http.pending-acquire-max:64}") int pendingAcquireMax,
            @Value("${app.http.pending-acquire-timeout-ms:250}") long pendingAcquireTimeoutMs) {
        return ConnectionProvider.builder("order-http")
                .maxConnections(Math.max(1, Math.min(maxConnections, 200)))
                .pendingAcquireMaxCount(Math.max(0, Math.min(pendingAcquireMax, 500)))
                .pendingAcquireTimeout(Duration.ofMillis(Math.max(50, Math.min(pendingAcquireTimeoutMs, 5_000))))
                .maxIdleTime(Duration.ofSeconds(30))
                .build();
    }

    @Bean
    @LoadBalanced
    public WebClient.Builder webClientBuilder(
            ConnectionProvider orderHttpConnectionProvider,
            @Value("${app.http.connect-timeout-ms:500}") int connectTimeoutMs,
            @Value("${app.http.response-timeout-ms:3000}") long responseTimeoutMs) {
        HttpClient httpClient = HttpClient.create(orderHttpConnectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        Math.max(100, Math.min(connectTimeoutMs, 10_000)))
                .responseTimeout(Duration.ofMillis(Math.max(100, Math.min(responseTimeoutMs, 30_000))));
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(1024 * 1024)); // 1MB buffer
    }

    @Bean
    public WebClient webClient(WebClient.Builder webClientBuilder) {
        return webClientBuilder.build();
    }
    
    @Bean
    @LoadBalanced
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
