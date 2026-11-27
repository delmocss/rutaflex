package com.rutaflex.api.job;

import com.rutaflex.events.OptimizationCompleted;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Métricas de las optimizaciones, visibles en {@code /actuator/metrics}:
 * <ul>
 *   <li>{@code rutaflex.optimization.compute}: tiempo de cálculo de cada optimización.</li>
 *   <li>{@code rutaflex.optimization.km.saved} y {@code .saved.percent}: km (y %) que ahorra cada una frente al
 *       reparto en orden de llegada.</li>
 *   <li>{@code rutaflex.jobs.requested}, {@code rutaflex.jobs.finished{result}} y {@code rutaflex.jobs.reaped}:
 *       cuántos trabajos se piden, cómo terminan y cuántos tuvo que cerrar el vigilante.</li>
 * </ul>
 * Solo se registra cuando el resultado se aplica de verdad: un mensaje repetido o tardío (que no cambia nada) no
 * cuenta dos veces. Los km ahorrados nunca son negativos en la métrica (un resultado peor que la referencia cuenta 0).
 */
@Component
class JobMetrics {

    private final MeterRegistry registry;

    JobMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void requested() {
        registry.counter("rutaflex.jobs.requested").increment();
    }

    void completed(OptimizationCompleted event) {
        registry.counter("rutaflex.jobs.finished", "result", "completed").increment();
        Timer.builder("rutaflex.optimization.compute")
                .description("Tiempo de cálculo de una optimización")
                .register(registry)
                .record(Duration.ofMillis(Math.max(0, event.computeMillis())));

        double saved = Math.max(0, event.baselineKm() - event.totalKm());
        DistributionSummary.builder("rutaflex.optimization.km.saved")
                .description("Km que ahorra una optimización frente al reparto en orden de llegada")
                .baseUnit("kilometers")
                .register(registry)
                .record(saved);
        if (event.baselineKm() > 0) {
            DistributionSummary.builder("rutaflex.optimization.saved.percent")
                    .description("Porcentaje de km que ahorra una optimización frente al orden de llegada")
                    .baseUnit("percent")
                    .register(registry)
                    .record(100 * saved / event.baselineKm());
        }
    }

    void failed() {
        registry.counter("rutaflex.jobs.finished", "result", "failed").increment();
    }

    void reaped(int jobs) {
        registry.counter("rutaflex.jobs.reaped").increment(jobs);
    }
}
