package com.rutaflex.api.sample;

import java.time.LocalDate;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Carga de datos de demostracion ({@code rutaflex.sample-data.*}). Desactivada por defecto.
 *
 * @param deliveryDate dia al que se asignan las paradas de ejemplo; si no se indica, hoy
 */
@ConfigurationProperties(prefix = "rutaflex.sample-data")
public record SampleDataProperties(@DefaultValue("false") boolean enabled, LocalDate deliveryDate) {
}
