package com.rutaflex.api.common;

import java.util.List;

/** El CSV no es valido: no se importa nada. {@code errors} lista cada problema con su linea. */
public class CsvImportException extends RuntimeException {

    private final transient List<String> errors;

    public CsvImportException(String message, List<String> errors) {
        super(message);
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
