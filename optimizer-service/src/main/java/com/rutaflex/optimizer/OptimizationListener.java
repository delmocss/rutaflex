package com.rutaflex.optimizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rutaflex.events.OptimizationRequested;
import com.rutaflex.events.Topics;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Entrada de Kafka. Una petición ilegible no se puede calcular ni reintentar (nunca se volverá legible), pero
 * si se sabe de qué trabajo es (la clave del mensaje es su id) se avisa a la API para que lo marque FAILED.
 */
@Component
class OptimizationListener {

    private static final Logger log = LoggerFactory.getLogger(OptimizationListener.class);

    private final OptimizationHandler handler;
    private final FailureReporter failures;
    private final ObjectMapper json;

    OptimizationListener(OptimizationHandler handler, FailureReporter failures, ObjectMapper json) {
        this.handler = handler;
        this.failures = failures;
        this.json = json;
    }

    @KafkaListener(topics = Topics.OPTIMIZATION_REQUESTED)
    void onRequested(ConsumerRecord<String, String> record) {
        OptimizationRequested request;
        try {
            request = json.readValue(record.value(), OptimizationRequested.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.error("Unreadable optimization request (key {}): {}", record.key(), e.getMessage());
            failures.report(record.key(), record.value(),
                    "The optimization request could not be read by the optimizer", 1);
            return;
        }
        if (request == null || request.jobId() == null) {
            log.error("Optimization request without jobId (key {})", record.key());
            failures.report(record.key(), record.value(), "The optimization request has no job id", 1);
            return;
        }
        handler.handle(request, Instant.ofEpochMilli(record.timestamp()));
    }
}
