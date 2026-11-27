package com.rutaflex.optimizer;

import static org.assertj.core.api.Assertions.assertThat;

import com.rutaflex.events.OptimizationCompleted;
import com.rutaflex.optimizer.solver.Assumptions;
import com.rutaflex.optimizer.solver.RouteSolver;
import org.junit.jupiter.api.Test;

/**
 * La cifra de ahorro del README solo vale si se puede repetir. Con un límite de pasos en vez de tiempo, la misma
 * entrada y la misma semilla tienen que dar exactamente el mismo resultado, sin depender de la máquina.
 */
class ReproducibilityTest {

    /** Ver {@link #theFullDatasetGivesAPinnedResultForASeedAndStepLimit()}. */
    private static final double PINNED_KM_3000_STEPS_SEED_1 = 221.03;

    private static RouteOptimizer optimizer(int steps, long seed) {
        return new RouteOptimizer(RouteSolver.withStepLimit(steps, seed), Assumptions.DEFAULT);
    }

    @Test
    void theSameSeedAndStepsGiveExactlyTheSameRoutes() {
        var request = SampleDataset.request(SampleDataset.stops().subList(0, 30), SampleDataset.fleet());

        OptimizationCompleted first = optimizer(1500, 7).optimize(request);
        OptimizationCompleted second = optimizer(1500, 7).optimize(request);

        assertThat(second.totalKm()).isEqualTo(first.totalKm());
        assertThat(second.baselineKm()).isEqualTo(first.baselineKm());
        assertThat(second.routes()).isEqualTo(first.routes());
    }

    /**
     * El valor está fijado: si cambia, o ha cambiado el modelo (restricciones, supuestos, referencias de tiempos...)
     * y hay que actualizarlo a propósito, o la búsqueda ya no es reproducible. Como se ejecuta en el CI (Linux)
     * y en local (Windows), también comprueba, de paso, que la misma semilla da el mismo resultado en ambos.
     */
    @Test
    void theFullDatasetGivesAPinnedResultForASeedAndStepLimit() {
        OptimizationCompleted result = optimizer(3000, 1).optimize(SampleDataset.request());

        assertThat(result.totalKm()).isEqualTo(PINNED_KM_3000_STEPS_SEED_1);
        assertThat(result.baselineKm()).isEqualTo(793.8);
    }

    @Test
    void moreStepsNeverGiveAWorseResultForTheSameSeed() {
        var request = SampleDataset.request(SampleDataset.stops().subList(0, 30), SampleDataset.fleet());

        double few = optimizer(300, 7).optimize(request).totalKm();
        double many = optimizer(3000, 7).optimize(request).totalKm();

        // La busqueda solo acepta mejoras del mejor resultado conocido: con mas pasos no puede salir peor.
        assertThat(many).isLessThanOrEqualTo(few);
    }

    @Test
    void theResultIsFeasibleAndBeatsTheBaselineEvenWithFewSteps() {
        var request = SampleDataset.request();

        OptimizationCompleted result = optimizer(2500, 1).optimize(request);

        assertThat(result.totalKm()).isLessThan(result.baselineKm());
        assertThat(result.routes().stream().mapToInt(r -> r.stops().size()).sum()).isEqualTo(80);
    }
}
