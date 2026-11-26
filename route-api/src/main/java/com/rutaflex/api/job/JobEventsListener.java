package com.rutaflex.api.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rutaflex.events.OptimizationCompleted;
import com.rutaflex.events.OptimizationFailed;
import com.rutaflex.events.OptimizationStarted;
import com.rutaflex.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Recibe el avance del optimizador. Un mensaje ilegible no se reintenta (nunca se volverá legible): se registra
 * y se descarta. Un fallo transitorio al guardar (base de datos caída) sí sube como excepción y Kafka reintenta.
 */
@Component
class JobEventsListener {

    private static final Logger log = LoggerFactory.getLogger(JobEventsListener.class);

    private final OptimizationJobService jobs;
    private final ObjectMapper json;

    JobEventsListener(OptimizationJobService jobs, ObjectMapper json) {
        this.jobs = jobs;
        this.json = json;
    }

    @KafkaListener(topics = Topics.OPTIMIZATION_STARTED)
    void onStarted(String payload) {
        OptimizationStarted event = parse(payload, OptimizationStarted.class);
        if (event != null) {
            jobs.onStarted(event.jobId());
        }
    }

    @KafkaListener(topics = Topics.OPTIMIZATION_COMPLETED)
    void onCompleted(String payload) {
        OptimizationCompleted event = parse(payload, OptimizationCompleted.class);
        if (event != null) {
            jobs.onCompleted(event);
        }
    }

    @KafkaListener(topics = Topics.OPTIMIZATION_FAILED)
    void onFailed(String payload) {
        OptimizationFailed event = parse(payload, OptimizationFailed.class);
        if (event != null) {
            jobs.onFailed(event.jobId(), event.reason());
        }
    }

    private <T> T parse(String payload, Class<T> type) {
        try {
            return json.readValue(payload, type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.error("Discarding an unreadable {} message: {}", type.getSimpleName(), e.getMessage());
            return null;
        }
    }
}
