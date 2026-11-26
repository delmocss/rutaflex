package com.rutaflex.optimizer;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxRequestAge   las peticiones más antiguas que esto se ignoran (ver {@link OptimizationHandler})
 * @param spentLimit      tiempo máximo de cálculo por trabajo
 * @param unimprovedLimit se detiene antes si la solución no mejora durante este tiempo
 * @param randomSeed      semilla de la búsqueda
 */
@ConfigurationProperties(prefix = "rutaflex.optimizer")
public record OptimizerProperties(Duration spentLimit, Duration unimprovedLimit, long randomSeed,
                                  Duration maxRequestAge) {

    public OptimizerProperties {
        if (spentLimit == null || spentLimit.isZero() || spentLimit.isNegative()) {
            spentLimit = Duration.ofSeconds(30);
        }
        if (unimprovedLimit == null || unimprovedLimit.isZero() || unimprovedLimit.isNegative()) {
            unimprovedLimit = Duration.ofSeconds(8);
        }
        if (maxRequestAge == null || maxRequestAge.isZero() || maxRequestAge.isNegative()) {
            maxRequestAge = Duration.ofHours(1);
        }
    }
}
