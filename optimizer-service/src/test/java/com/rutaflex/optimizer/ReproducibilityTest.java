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
