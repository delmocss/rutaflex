package com.rutaflex.optimizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rutaflex.optimizer.solver.Assumptions;
import com.rutaflex.optimizer.solver.RouteSolver;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@EnableConfigurationProperties(OptimizerProperties.class)
class OptimizerConfig {

    /** Intentos totales antes de rendirse: el primero más los reintentos del back-off. */
    private static final int RETRIES = 2;

    @Bean
    RouteOptimizer routeOptimizer(OptimizerProperties properties) {
        return new RouteOptimizer(
                new RouteSolver(properties.spentLimit(), properties.unimprovedLimit(), properties.randomSeed()),
                Assumptions.DEFAULT);
    }

    @Bean
    OptimizationHandler optimizationHandler(RouteOptimizer optimizer, EventPublisher publisher,
                                            OptimizerProperties properties) {
        return new OptimizationHandler(optimizer, publisher, properties.maxRequestAge());
    }

    @Bean
    FailureReporter failureReporter(EventPublisher publisher, ObjectMapper json) {
        return new FailureReporter(publisher, json);
    }

    /**
     * Reintenta los fallos inesperados (dos veces, con una pausa) y, si siguen fallando, avisa con
     * {@code optimization.failed} para que la API marque el trabajo como FALLIDO en vez de dejarlo esperando.
     * El aviso no lanza nunca (ver {@link FailureReporter}), así que no puede provocar reentregas en bucle.
     */
    @Bean
    CommonErrorHandler kafkaErrorHandler(FailureReporter failures) {
        return new DefaultErrorHandler((record, error) -> {
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            // Solo el tipo de la excepcion: el mensaje puede contener detalles internos.
            failures.report(record.key() instanceof String key ? key : null,
                    record.value() instanceof String value ? value : null,
                    "The optimizer could not process the request after " + (RETRIES + 1) + " attempts ("
                            + cause.getClass().getSimpleName() + ")",
                    RETRIES + 1);
        }, new FixedBackOff(2000L, RETRIES));
    }
}
