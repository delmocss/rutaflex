package com.rutaflex.api.common;

/** La peticion es valida pero choca con el estado actual (por ejemplo, ya hay un calculo en curso). */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
