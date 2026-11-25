package com.rutaflex.api.sample;

import com.rutaflex.api.common.GeoPoint;
import com.rutaflex.api.stop.ImportResult;
import com.rutaflex.api.stop.StopService;
import com.rutaflex.api.vehicle.VehicleRequest;
import com.rutaflex.api.vehicle.VehicleService;
import com.rutaflex.api.warehouse.WarehouseRequest;
import com.rutaflex.api.warehouse.WarehouseResponse;
import com.rutaflex.api.warehouse.WarehouseService;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Con {@code rutaflex.sample-data.enabled=true} crea un almacen, cuatro furgonetas y las 80 paradas
 * de Madrid sur (dataset sintetico, ver {@code data/generate_madrid_sur_stops.py}). Pasa por los
 * mismos servicios y validaciones que la API. Idempotente: si ya hay datos, no toca nada.
 */
@Component
@ConditionalOnProperty(prefix = "rutaflex.sample-data", name = "enabled", havingValue = "true")
public class SampleDataSeeder implements ApplicationRunner {

    static final String STOPS_RESOURCE = "sample/madrid-sur-stops.csv";
    private static final Logger log = LoggerFactory.getLogger(SampleDataSeeder.class);

    private final SampleDataProperties properties;
    private final WarehouseService warehouses;
    private final VehicleService vehicles;
    private final StopService stops;

    SampleDataSeeder(SampleDataProperties properties, WarehouseService warehouses,
                     VehicleService vehicles, StopService stops) {
        this.properties = properties;
        this.warehouses = warehouses;
        this.vehicles = vehicles;
        this.stops = stops;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!warehouses.list().isEmpty()) {
            log.info("Sample data skipped: there are already warehouses");
            return;
        }

        WarehouseResponse warehouse = warehouses.create(new WarehouseRequest(
                "Almacen Madrid Sur",
                "Poligono industrial de ejemplo, Valdemoro",
                new GeoPoint(40.1985, -3.6560)));

        // Capacidad total 240 bultos para unos 205: ajustada, como en un reparto real.
        vehicles.create(warehouse.id(), van("Furgoneta 1", 8));
        vehicles.create(warehouse.id(), van("Furgoneta 2", 8));
        vehicles.create(warehouse.id(), van("Furgoneta 3", 9));
        vehicles.create(warehouse.id(), van("Furgoneta 4", 9));

        LocalDate date = properties.deliveryDate() != null ? properties.deliveryDate() : LocalDate.now();
        try (InputStream csv = new ClassPathResource(STOPS_RESOURCE).getInputStream()) {
            ImportResult result = stops.importCsv(date, csv, true);
            log.info("Sample data loaded: 1 warehouse, 4 vehicles and {} stops for {}", result.imported(), date);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + STOPS_RESOURCE, e);
        }
    }

    private static VehicleRequest van(String name, int startHour) {
        return new VehicleRequest(name, 60, LocalTime.of(startHour, 0), LocalTime.of(startHour + 7, 0));
    }
}
