package com.rutaflex.optimizer;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Memoria de los trabajos ya atendidos, para no recalcular el mismo mensaje si Kafka lo entrega dos veces.
 * Está acotada (olvida los más antiguos) y vive en memoria: tras un reinicio se pierde. Es una optimización,
 * no la garantía: el consumidor de resultados de la API es idempotente por sí mismo, así que un cálculo
 * repetido nunca duplica nada, solo gasta CPU.
 */
class ProcessedJobs {

    private final int capacity;
    private final Set<UUID> ids = new LinkedHashSet<>();

    ProcessedJobs(int capacity) {
        this.capacity = capacity;
    }

    /** @return {@code true} si es la primera vez que se ve este trabajo (y queda registrado) */
    synchronized boolean begin(UUID jobId) {
        if (!ids.add(jobId)) {
            return false;
        }
        if (ids.size() > capacity) {
            var oldest = ids.iterator();
            oldest.next();
            oldest.remove();
        }
        return true;
    }

    /** Si el intento falló de forma reintentable, el reintento debe poder volver a entrar. */
    synchronized void forget(UUID jobId) {
        ids.remove(jobId);
    }
}
