package com.rutaflex.optimizer;

/** El problema no se puede resolver; el mensaje explica por qué (irá en el evento optimization.failed). */
public class OptimizationException extends RuntimeException {

    public OptimizationException(String message) {
        super(message);
    }
}
