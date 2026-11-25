package com.rutaflex.api.stop;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Limites de la importacion CSV ({@code rutaflex.import.*}). */
@ConfigurationProperties(prefix = "rutaflex.import")
public record ImportProperties(@DefaultValue("1000") int maxRows) {
}
