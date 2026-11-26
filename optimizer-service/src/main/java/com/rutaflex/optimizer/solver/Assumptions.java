package com.rutaflex.optimizer.solver;

/**
 * Supuestos del modelo de tiempos y distancias. No hay red viaria: la distancia es la de la línea recta
 * (haversine) multiplicada por un factor de rodeo, y el tiempo sale de una velocidad media urbana.
 * Son estimaciones, no rutas reales por calle; el mismo modelo se aplica a la solución optimizada y a la
 * línea base, de modo que la comparación entre ambas es justa aunque las cifras absolutas no sean exactas.
 *
 * @param detourFactor         relación típica entre distancia por carretera y en línea recta en zona urbana
 * @param averageSpeedKmh      velocidad media de la furgoneta
 * @param serviceSecondsBase   tiempo fijo por parada (aparcar, llamar, firmar)
 * @param serviceSecondsParcel tiempo adicional por bulto
 */
public record Assumptions(double detourFactor, double averageSpeedKmh, int serviceSecondsBase,
                          int serviceSecondsParcel) {

    public static final Assumptions DEFAULT = new Assumptions(1.3, 30.0, 120, 30);

    public Assumptions {
        if (detourFactor < 1.0 || averageSpeedKmh <= 0 || serviceSecondsBase < 0 || serviceSecondsParcel < 0) {
            throw new IllegalArgumentException("Invalid routing assumptions");
        }
    }

    /** Distancia estimada por carretera, en metros. */
    public int distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double earthRadius = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
        double straight = 2 * earthRadius * Math.asin(Math.min(1.0, Math.sqrt(a)));
        return (int) Math.round(straight * detourFactor);
    }

    public int travelSeconds(int meters) {
        return (int) Math.round(meters / (averageSpeedKmh / 3.6));
    }

    public int serviceSeconds(int parcels) {
        return serviceSecondsBase + serviceSecondsParcel * parcels;
    }
}
