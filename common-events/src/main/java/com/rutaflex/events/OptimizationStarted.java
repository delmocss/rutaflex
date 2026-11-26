package com.rutaflex.events;

import java.util.UUID;

/** Evento {@code optimization.started}: el optimizador ha empezado a calcular; el trabajo pasa a RUNNING. */
public record OptimizationStarted(UUID jobId) {
}
