package com.rutaflex.optimizer;

import com.rutaflex.events.OptimizationCompleted;
import com.rutaflex.events.OptimizationFailed;
import com.rutaflex.events.OptimizationRequested;
import com.rutaflex.events.OptimizationStarted;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Atiende una petición: avisa de que empieza, calcula y publica el resultado.
 * <ul>
 *   <li>Problema sin solución ({@link OptimizationException}): es definitivo, se publica {@code failed} y no se reintenta.</li>
 *   <li>Cualquier otro fallo (Kafka caído, error inesperado): se relanza para que el gestor de errores reintente,
 *       y al agotar los intentos publique {@code failed}.</li>
 *   <li>Una petición repetida (mismo {@code jobId}) se ignora.</li>
 * </ul>
 */
public class OptimizationHandler {

    private static final Logger log = LoggerFactory.getLogger(OptimizationHandler.class);

    private final RouteOptimizer optimizer;
    private final EventPublisher publisher;
    private final ProcessedJobs processed = new ProcessedJobs(1000);

    public OptimizationHandler(RouteOptimizer optimizer, EventPublisher publisher) {
        this.optimizer = optimizer;
        this.publisher = publisher;
    }

    public void handle(OptimizationRequested request) {
        UUID jobId = request.jobId();
        if (!processed.begin(jobId)) {
            log.info("Job {}: duplicate request ignored", jobId);
            return;
        }
        try {
            publisher.started(new OptimizationStarted(jobId));
            try {
                OptimizationCompleted result = optimizer.optimize(request);
                publisher.completed(result);
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
}
