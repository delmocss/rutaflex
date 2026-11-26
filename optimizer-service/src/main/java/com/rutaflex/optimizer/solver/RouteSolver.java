package com.rutaflex.optimizer.solver;

import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import java.time.Duration;

/** Envuelve la configuración de Timefold. Cada llamada a {@link #solve} usa su propio solver (no hay estado compartido). */
public class RouteSolver {

    private final SolverFactory<RoutePlan> factory;

    /**
     * @param spentLimit      tiempo máximo de cálculo
     * @param unimprovedLimit se para antes si no mejora durante este tiempo
     * @param randomSeed      semilla fija: con la misma entrada y la misma semilla la búsqueda es reproducible
     *                        (el resultado exacto depende además de cuánto alcance a calcular en el tiempo dado)
     */
    public RouteSolver(Duration spentLimit, Duration unimprovedLimit, long randomSeed) {
        this(spentLimit, unimprovedLimit, randomSeed, EnvironmentMode.PHASE_ASSERT);
    }

    /** Con FULL_ASSERT el solver comprueba tras cada movimiento que la puntuación incremental coincide con la recalculada. */
    public RouteSolver(Duration spentLimit, Duration unimprovedLimit, long randomSeed, EnvironmentMode mode) {
        SolverConfig config = new SolverConfig()
                .withEnvironmentMode(mode)
                .withSolutionClass(RoutePlan.class)
                .withEntityClasses(RouteVehicle.class, Visit.class)
                .withConstraintProviderClass(RouteConstraints.class)
                .withRandomSeed(randomSeed)
                .withTerminationConfig(new TerminationConfig()
                        .withSpentLimit(spentLimit)
                        .withUnimprovedSpentLimit(unimprovedLimit));
        this.factory = SolverFactory.create(config);
    }

    public RoutePlan solve(RoutePlan problem) {
        return factory.buildSolver().solve(problem);
    }
}
