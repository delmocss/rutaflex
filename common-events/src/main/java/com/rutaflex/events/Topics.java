package com.rutaflex.events;

/** Nombres de los topics de Kafka. Un solo sitio para que API y optimizador no se desincronicen. */
public final class Topics {

    /** La API pide una optimizacion: lleva el problema completo (almacen, furgonetas, paradas). */
    public static final String OPTIMIZATION_REQUESTED = "optimization.requested";

    /** El optimizador devuelve las rutas calculadas. */
    public static final String OPTIMIZATION_COMPLETED = "optimization.completed";

    /** Dead letter: mensajes que siguen fallando tras los reintentos. El trabajo queda FALLIDO. */
    public static final String OPTIMIZATION_FAILED = "optimization.failed";

    private Topics() {
    }
}
