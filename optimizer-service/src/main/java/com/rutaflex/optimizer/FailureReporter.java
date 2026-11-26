package com.rutaflex.optimizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rutaflex.events.OptimizationFailed;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Avisa a la API de que una petición no se pudo atender, para que el trabajo acabe FAILED en vez de esperar.
 * Identifica el trabajo por la clave del mensaje (que es el jobId) o, si no, por el campo {@code jobId} del
 * cuerpo. Nunca lanza: se usa desde un gestor de errores, donde una excepción provocaría reentregas en bucle.
 * Si no se puede avisar, queda el vigilante de trabajos atascados de la API.
 */
class FailureReporter {

    private static final Logger log = LoggerFactory.getLogger(FailureReporter.class);

    private final EventPublisher publisher;
    private final ObjectMapper json;

    FailureReporter(EventPublisher publisher, ObjectMapper json) {
        this.publisher = publisher;
        this.json = json;
    }

    /** @return si se llegó a publicar el aviso */
    boolean report(String key, String payload, String reason, int attempts) {
        UUID jobId = jobId(key, payload);
        if (jobId == null) {
            log.error("A message could not be processed and has no readable job id, so nobody can be told: {}", reason);
            return false;
        }
        try {
            publisher.failed(new OptimizationFailed(jobId, reason, attempts));
            return true;
        } catch (RuntimeException e) {
            log.error("Job {}: could not publish the failure notice ({}); the API will close the job on timeout",
                    jobId, reason, e);
            return false;
        }
    }

    private UUID jobId(String key, String payload) {
        UUID fromKey = uuid(key);
        if (fromKey != null) {
            return fromKey;
        }
        if (payload == null) {
            return null;
        }
        try {
            JsonNode id = json.readTree(payload).path("jobId");
            return id.isTextual() ? uuid(id.asText()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static UUID uuid(String text) {
        try {
            return text == null ? null : UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
