package com.rutaflex.api.common;

/** La peticion esta bien formada pero no se puede atender: faltan datos previos (paradas, furgonetas...). */
public class UnprocessableException extends RuntimeException {

    public UnprocessableException(String message) {
        super(message);
    }
}
