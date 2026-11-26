package com.rutaflex.optimizer;

import com.rutaflex.events.OptimizationCompleted;
import com.rutaflex.events.OptimizationFailed;
import com.rutaflex.events.OptimizationStarted;

/** Salida del optimizador hacia el resto del sistema. Una interfaz para poder probar la lógica sin Kafka. */
public interface EventPublisher {

    void started(OptimizationStarted event);

    void completed(OptimizationCompleted event);

    void failed(OptimizationFailed event);
}
