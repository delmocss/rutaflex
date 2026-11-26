package com.rutaflex.optimizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rutaflex.events.OptimizationFailed;
import com.rutaflex.optimizer.solver.Assumptions;
import com.rutaflex.optimizer.solver.RouteSolver;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@EnableConfigurationProperties(OptimizerProperties.class)
class OptimizerConfig {

    private static final Logger log = LoggerFactory.getLogger(OptimizerConfig.class);

    /** Intentos totales antes de rendirse: el primero más los reintentos del back-off. */
    private static final int RETRIES = 2;

    @Bean
    RouteOptimizer routeOptimizer(OptimizerProperties properties) {
        return new RouteOptimizer(
                new RouteSolver(properties.spentLimit(), properties.unimprovedLimit(), properties.randomSeed()),
                Assumptions.DEFAULT);
    }

    @Bean
    OptimizationHandler optimizationHandler(RouteOptimizer optimizer, EventPublisher publisher) {
        return new OptimizationHandler(optimizer, publisher);
    }

    /**
     * Reintenta los fallos inesperados (dos veces, con una pausa) y, si siguen fallando, publica
     * {@code optimization.failed} para que la API marque el trabajo como FALLIDO en vez de dejarlo colgado.
     */
    @Bean
    CommonErrorHandler kafkaErrorHandler(EventPublisher publisher, ObjectMapper json) {
        return new DefaultErrorHandler((record, error) -> {
            UUID jobId = jobIdOf(record.value(), json);
            if (jobId == null) {
                log.error("Giving up on a message without a readable jobId; nothing to report", error);
                return;
            }
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            log.error("Job {}: giving up after {} attempts", jobId, RETRIES + 1, error);
            publisher.failed(new OptimizationFailed(jobId,
                    "The optimizer could not process the request: " + cause.getMessage(), RETRIES + 1));
        }, new FixedBackOff(2000L, RETRIES));
    }

    private static UUID jobIdOf(Object value, ObjectMapper json) {
        if (!(value instanceof String text)) {
            return null;
        }
        try {
            JsonNode id = json.readTree(text).path("jobId");
            return id.isTextual() ? UUID.fromString(id.asText()) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
