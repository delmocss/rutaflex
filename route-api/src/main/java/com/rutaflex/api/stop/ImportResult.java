package com.rutaflex.api.stop;

import java.time.LocalDate;

/** {@code replaced}: paradas del dia que se borraron antes de importar (solo con {@code replace=true}). */
public record ImportResult(LocalDate date, int imported, int replaced) {
}
