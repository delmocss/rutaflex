package com.rutaflex.api.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rutaflex.events.OptimizationRequested;
import com.rutaflex.events.Topics;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publica {@code optimization.requested} y espera la confirmación del broker: si no llega, el trabajo no está
 * realmente en cola y hay que decirlo. La clave del mensaje es el id del trabajo.
 */
@Component
public class OptimizationRequestPublisher {

    // Mayor que delivery.timeout.ms del productor (8 s, ver application.yml): cuando get() expira, el productor
    // ya ha desistido de verdad y el mensaje no puede llegar despues. Asi un 503 significa "no se envio".
    private static final long CONFIRMATION_SECONDS = 15;

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    OptimizationRequestPublisher(KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    /** @throws IllegalStateException si el mensaje no se pudo entregar al broker */
    public void publish(OptimizationRequested event) {
        try {
            kafka.send(Topics.OPTIMIZATION_REQUESTED, event.jobId().toString(), json.writeValueAsString(event))
                    .get(CONFIRMATION_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing the optimization request", e);
        } catch (ExecutionException | TimeoutException | JsonProcessingException e) {
            throw new IllegalStateException("Could not publish the optimization request", e);
        }
    }
}
