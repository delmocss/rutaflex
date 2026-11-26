package com.rutaflex.optimizer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

// Sin broker: el listener no arranca (este test solo comprueba que el servicio levanta y responde).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.listener.auto-startup=false")
class OptimizerApplicationTest {

    @Autowired
    TestRestTemplate http;

    @Test
    void startsAndReportsHealthy() {
        var response = http.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
