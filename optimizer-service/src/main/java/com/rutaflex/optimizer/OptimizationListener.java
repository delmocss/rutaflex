package com.rutaflex.optimizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rutaflex.events.OptimizationRequested;
import com.rutaflex.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Entrada de Kafka. Un mensaje ilegible no se puede atender ni reintentar (ni siquiera tiene jobId): se registra y se descarta. */
@Component
class OptimizationListener {

    private static final Logger log = LoggerFactory.getLogger(OptimizationListener.class);

    private final OptimizationHandler handler;
    private final ObjectMapper json;

    OptimizationListener(OptimizationHandler handler, ObjectMapper json) {
        this.handler = handler;
        this.json = json;
    }

    @KafkaListener(topics = Topics.OPTIMIZATION_REQUESTED)
    void onRequested(String payload) {
        OptimizationRequested request;
        try {
            request = json.readValue(payload, OptimizationRequested.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.error("Discarding an unreadable optimization request: {}", e.getMessage());
            return;
        }
        if (request == null || request.jobId() == null) {
            log.error("Discarding an optimization request without jobId");
            return;
        }
        handler.handle(request);
    }
}
