package com.rutaflex.optimizer;

import com.rutaflex.events.OptimizationCompleted;
import com.rutaflex.events.OptimizationFailed;
import com.rutaflex.events.OptimizationRequested;
import com.rutaflex.events.OptimizationStarted;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Atiende una petición: avisa de que empieza, calcula y publica el resultado.
 * <ul>
 *   <li>Problema sin solución ({@link OptimizationException}): es definitivo, se publica {@code failed} y no se reintenta.</li>
 *   <li>Cualquier otro fallo (Kafka caído, error inesperado): se relanza para que el gestor de errores reintente,
 *       y al agotar los intentos publique {@code failed}. Si lo que falló fue publicar el resultado, este se
 *       guarda para el reintento: no tiene sentido volver a calcular durante una caída de Kafka.</li>
 *   <li>Una petición repetida (mismo {@code jobId}) se ignora.</li>
 *   <li>Una petición demasiado antigua se ignora: la API ya habrá cerrado ese trabajo por plazo, y calcularla
 *       solo retrasaría a las peticiones vigentes (pasa al reiniciar con un grupo nuevo, que relee el topic).</li>
 * </ul>
 */
public class OptimizationHandler {

    private static final Logger log = LoggerFactory.getLogger(OptimizationHandler.class);
    private static final int MAX_UNSENT = 100;

    private final RouteOptimizer optimizer;
    private final EventPublisher publisher;
    private final Duration maxRequestAge;
    private final ProcessedJobs processed = new ProcessedJobs(1000);

    /** Resultados calculados cuya publicación falló, a la espera del reintento. Acotado. */
    private final Map<UUID, OptimizationCompleted> unsent = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, OptimizationCompleted> eldest) {
            return size() > MAX_UNSENT;
        }
    };

    public OptimizationHandler(RouteOptimizer optimizer, EventPublisher publisher, Duration maxRequestAge) {
        this.optimizer = optimizer;
        this.publisher = publisher;
        this.maxRequestAge = maxRequestAge;
    }

    public OptimizationHandler(RouteOptimizer optimizer, EventPublisher publisher) {
        this(optimizer, publisher, Duration.ofHours(1));
    }

    public void handle(OptimizationRequested request) {
        handle(request, Instant.now());
    }

    /** @param publishedAt cuándo se publicó la petición en Kafka */
    public void handle(OptimizationRequested request, Instant publishedAt) {
        UUID jobId = request.jobId();
        if (Duration.between(publishedAt, Instant.now()).compareTo(maxRequestAge) > 0) {
            log.warn("Job {}: request published at {} is older than {}, ignored", jobId, publishedAt, maxRequestAge);
            return;
        }
        if (!processed.begin(jobId)) {
            log.info("Job {}: duplicate request ignored", jobId);
            return;
        }
        try {
            publisher.started(new OptimizationStarted(jobId));
            try {
                OptimizationCompleted result = takeUnsent(jobId);
                if (result == null) {
                    result = optimizer.optimize(request);
                }
                try {
                    publisher.completed(result);
                } catch (RuntimeException e) {
                    keepUnsent(jobId, result);
                    throw e;
                }
                log.info("Job {}: {} km (baseline {} km) in {} ms", jobId, result.totalKm(), result.baselineKm(),
                        result.computeMillis());
            } catch (OptimizationException e) {
                log.info("Job {}: no solution: {}", jobId, e.getMessage());
                publisher.failed(new OptimizationFailed(jobId, e.getMessage(), 1));
            }
        } catch (RuntimeException e) {
            processed.forget(jobId);
            throw e;
        }
    }

    private synchronized OptimizationCompleted takeUnsent(UUID jobId) {
        return unsent.remove(jobId);
    }

    private synchronized void keepUnsent(UUID jobId, OptimizationCompleted result) {
        unsent.put(jobId, result);
    }
}
