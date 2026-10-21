package com.delivery.order_service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "management.endpoints.web.exposure.include=*",
                "management.prometheus.metrics.export.enabled=true",
                "management.metrics.export.prometheus.enabled=true"
        })
@ActiveProfiles("test")
class PrometheusEndpointIntegrationTest {

    @LocalManagementPort
    private int managementPort;

    @Autowired(required = false)
    private WebTestClient webTestClient;

    @Test
    void exposesPrometheusFormattedMetricsOnTheManagementServer() {
        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + managementPort)
                .build();

        client.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/plain")
                .expectBody(String.class)
                .value(body -> assertThat(body).isNotNull());
    }
}
