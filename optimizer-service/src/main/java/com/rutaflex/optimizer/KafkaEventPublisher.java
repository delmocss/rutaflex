package com.rutaflex.optimizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rutaflex.events.OptimizationCompleted;
import com.rutaflex.events.OptimizationFailed;
import com.rutaflex.events.OptimizationStarted;
import com.rutaflex.events.Topics;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Publica en Kafka y espera la confirmación del broker; si no llega, lanza para que el mensaje se reintente. */
@Component
class KafkaEventPublisher implements EventPublisher {

    private static final long CONFIRMATION_SECONDS = 10;

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    KafkaEventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    @Override
    public void started(OptimizationStarted event) {
        send(Topics.OPTIMIZATION_STARTED, event.jobId(), event);
    }

    @Override
    public void completed(OptimizationCompleted event) {
        send(Topics.OPTIMIZATION_COMPLETED, event.jobId(), event);
    }

    @Override
    public void failed(OptimizationFailed event) {
        send(Topics.OPTIMIZATION_FAILED, event.jobId(), event);
    }

    private void send(String topic, UUID jobId, Object event) {
        try {
            kafka.send(topic, jobId.toString(), json.writeValueAsString(event))
                    .get(CONFIRMATION_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing to " + topic, e);
        } catch (ExecutionException | TimeoutException | JsonProcessingException e) {
            throw new IllegalStateException("Could not publish to " + topic, e);
        }
    }
}
