#!/usr/bin/env python3
"""Genera el dataset de ejemplo de RutaFlex: 80 paradas de reparto en Madrid sur.

IMPORTANTE: el dataset es SINTETICO. Las direcciones son inventadas (nombre de calle + numero +
municipio) y las coordenadas se muestrean al azar dentro del casco urbano aproximado de cada
municipio; no corresponden a portales reales ni se han geocodificado. Sirve para probar y medir el
optimizador con un problema realista en tamano y distribucion, no para entregar paquetes.

Es determinista (semilla fija): ejecutarlo de nuevo produce exactamente el mismo CSV.

    python data/generate_madrid_sur_stops.py

Escribe route-api/src/main/resources/sample/madrid-sur-stops.csv
"""
import csv
import math
import random
from pathlib import Path

SEED = 20261125
OUTPUT = Path(__file__).resolve().parent.parent / "route-api" / "src" / "main" / "resources" / "sample" / "madrid-sur-stops.csv"

# (municipio, latitud del centro, longitud del centro, radio urbano aproximado en metros, numero de paradas)
TOWNS = [
    ("Valdemoro", 40.1908, -3.6742, 1500, 28),
    ("Pinto", 40.2428, -3.6997, 1600, 24),
    ("Getafe", 40.3057, -3.7329, 2300, 28),
]

STREETS = [
    "Calle Mayor", "Calle Real", "Avenida de España", "Calle del Sol", "Calle de la Iglesia",
    "Calle Madrid", "Avenida de la Constitución", "Calle del Pilar", "Calle de Cervantes",
    "Calle de Goya", "Calle de la Estación", "Calle Hospital", "Calle Toledo", "Calle Segovia",
    "Avenida de Andalucía", "Calle de los Olivos", "Calle de la Fuente", "Calle del Pinar",
    "Calle de San Isidro", "Calle de la Paz", "Calle Libertad", "Calle Zaragoza",
    "Paseo de la Alameda", "Calle de las Eras", "Calle del Carmen",
]

METERS_PER_DEGREE_LAT = 111_320.0


def random_point_in_disc(rng, lat, lon, radius_m):
    """Punto uniforme dentro de un circulo (sqrt para no concentrar puntos en el centro)."""
    distance = radius_m * math.sqrt(rng.random())
    angle = rng.random() * 2 * math.pi
    dlat = distance * math.cos(angle) / METERS_PER_DEGREE_LAT
    dlon = distance * math.sin(angle) / (METERS_PER_DEGREE_LAT * math.cos(math.radians(lat)))
    return round(lat + dlat, 6), round(lon + dlon, 6)


def main():
    rng = random.Random(SEED)
    rows = []
    for town, lat, lon, radius, count in TOWNS:
        for _ in range(count):
            point_lat, point_lon = random_point_in_disc(rng, lat, lon, radius)
            address = f"{rng.choice(STREETS)}, {rng.randint(1, 90)}, {town}"
            parcels = rng.choices([1, 2, 3, 4, 5, 6], weights=[30, 28, 20, 12, 7, 3])[0]
            # ~35 % de las paradas tienen franja horaria de dos horas.
            if rng.random() < 0.35:
                start = rng.choice([9, 10, 11, 12])
                window = (f"{start:02d}:00", f"{start + 2:02d}:00")
            else:
                window = ("", "")
            rows.append([address, f"{point_lat:.6f}", f"{point_lon:.6f}", parcels, window[0], window[1]])

    rng.shuffle(rows)  # el orden de llegada no sigue los municipios: asi la linea base "sin optimizar" es honesta

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with OUTPUT.open("w", encoding="utf-8", newline="\n") as out:
        writer = csv.writer(out, lineterminator="\n")
        writer.writerow(["address", "lat", "lon", "parcels", "window_start", "window_end"])
        writer.writerows(rows)

    parcels = sum(row[3] for row in rows)
    windows = sum(1 for row in rows if row[4])
    print(f"{len(rows)} paradas, {parcels} bultos, {windows} con franja horaria -> {OUTPUT}")


if __name__ == "__main__":
    main()
