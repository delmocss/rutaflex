package com.rutaflex.events;

import java.util.UUID;

/** Evento {@code optimization.failed} (dead letter): el trabajo queda FALLIDO con este motivo. */
public record OptimizationFailed(UUID jobId, String reason, int attempts) {
}
