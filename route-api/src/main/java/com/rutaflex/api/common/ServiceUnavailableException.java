package com.rutaflex.api.common;

/** Una dependencia (Kafka) no responde: es un 503 reintentable, no un fallo de la peticion. */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
