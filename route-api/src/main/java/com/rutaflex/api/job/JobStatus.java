package com.rutaflex.api.job;

/**
 * Ciclo de vida de un trabajo: QUEUED -> RUNNING -> COMPLETED | FAILED (RUNNING puede saltarse si el aviso
 * de inicio llega después del resultado). COMPLETED y FAILED son finales: ningún mensaje posterior los cambia.
 */
public enum JobStatus {
    QUEUED, RUNNING, COMPLETED, FAILED
}
