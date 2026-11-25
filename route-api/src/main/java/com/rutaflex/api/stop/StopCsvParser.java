package com.rutaflex.api.stop;

import com.rutaflex.api.common.CsvImportException;
import com.rutaflex.api.common.GeoPoint;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * Lee un CSV de paradas y lo valida entero antes de devolver nada: si una sola fila es
 * invalida no se importa ninguna. Formato: UTF-8 (con o sin BOM), separador coma, decimales
 * con punto, cabecera obligatoria (el orden y las mayusculas dan igual):
 * {@code address, lat, lon, parcels} y opcionalmente {@code window_start, window_end} (HH:mm).
 * Las filas se numeran desde 1 sin contar la cabecera.
 */
@Component
class StopCsvParser {

    private static final int MAX_REPORTED_ERRORS = 25;
    private static final Set<String> REQUIRED = Set.of("address", "lat", "lon", "parcels");

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreHeaderCase(true)
            .setIgnoreEmptyLines(true)
            .setAllowDuplicateHeaderNames(false)
            .setTrim(true)
            .get();

    private final Validator validator;
    private final ImportProperties properties;

    StopCsvParser(Validator validator, ImportProperties properties) {
        this.validator = validator;
        this.properties = properties;
    }

    List<StopRequest> parse(InputStream input, LocalDate deliveryDate) {
        try (CSVParser csv = FORMAT.parse(withoutBom(input))) {
            requireColumns(csv.getHeaderNames());

            List<StopRequest> stops = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            for (CSVRecord record : csv) {
                long row = record.getRecordNumber();
                if (row > properties.maxRows()) {
                    throw new CsvImportException(
                            "The file has more than " + properties.maxRows() + " rows", List.of());
                }
                StopRequest stop = toRequest(record, deliveryDate, errors);
                if (stop != null) {
                    validate(stop, row, errors);
                    stops.add(stop);
                }
            }

            if (stops.isEmpty() && errors.isEmpty()) {
                throw new CsvImportException("The file has no rows", List.of());
            }
            if (!errors.isEmpty()) {
                throw new CsvImportException(
                        "The CSV is not valid, nothing was imported", truncate(errors));
            }
            return stops;
        } catch (IOException | UncheckedIOException | IllegalStateException | IllegalArgumentException e) {
            throw new CsvImportException("The file is not a well-formed CSV: " + e.getMessage(), List.of());
        }
    }

    private static void requireColumns(List<String> header) {
        List<String> lower = header.stream().map(h -> h.toLowerCase(java.util.Locale.ROOT)).toList();
        List<String> missing = REQUIRED.stream().filter(c -> !lower.contains(c)).sorted().toList();
        if (!missing.isEmpty()) {
            throw new CsvImportException(
                    "Missing required column(s): " + String.join(", ", missing),
                    List.of("Expected header: address,lat,lon,parcels[,window_start,window_end]"));
        }
    }

    private StopRequest toRequest(CSVRecord record, LocalDate deliveryDate, List<String> errors) {
        long row = record.getRecordNumber();
        int before = errors.size();

        Double lat = number(record, "lat", row, errors);
        Double lon = number(record, "lon", row, errors);
        Integer parcels = integer(record, "parcels", row, errors);
        LocalTime start = time(record, "window_start", row, errors);
        LocalTime end = time(record, "window_end", row, errors);

        if (errors.size() > before) {
            return null;
        }
        return new StopRequest(deliveryDate, text(record, "address"), new GeoPoint(lat, lon), parcels, start, end);
    }

    private void validate(StopRequest stop, long row, List<String> errors) {
        for (ConstraintViolation<StopRequest> violation : validator.validate(stop)) {
            errors.add("row " + row + ": " + column(violation.getPropertyPath().toString()) + " " + violation.getMessage());
        }
    }

    /** Nombre de la columna del CSV a la que corresponde una propiedad del DTO. */
    private static String column(String property) {
        return switch (property) {
            case "location.lat" -> "lat";
            case "location.lon" -> "lon";
            case "windowValid" -> "window_start/window_end";
            default -> property;
        };
    }

    private static String text(CSVRecord record, String column) {
        return record.isMapped(column) && record.isSet(column) ? record.get(column) : "";
    }

    private static Double number(CSVRecord record, String column, long row, List<String> errors) {
        try {
            return Double.valueOf(text(record, column));
        } catch (NumberFormatException e) {
            errors.add("row " + row + ": " + column + " is not a number (use a dot as the decimal separator)");
            return null;
        }
    }

    private static Integer integer(CSVRecord record, String column, long row, List<String> errors) {
        try {
            return Integer.valueOf(text(record, column));
        } catch (NumberFormatException e) {
            errors.add("row " + row + ": " + column + " is not a whole number");
            return null;
        }
    }

    private static LocalTime time(CSVRecord record, String column, long row, List<String> errors) {
        String value = text(record, column);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException e) {
            errors.add("row " + row + ": " + column + " must be a time as HH:mm");
            return null;
        }
    }

    private static List<String> truncate(List<String> errors) {
        if (errors.size() <= MAX_REPORTED_ERRORS) {
            return errors;
        }
        List<String> shown = new ArrayList<>(errors.subList(0, MAX_REPORTED_ERRORS));
        shown.add("... and " + (errors.size() - MAX_REPORTED_ERRORS) + " more");
        return shown;
    }

    /** Excel y algunos editores anaden un BOM al inicio del UTF-8: se descarta. */
    private static PushbackReader withoutBom(InputStream input) throws IOException {
        PushbackReader reader = new PushbackReader(new InputStreamReader(input, StandardCharsets.UTF_8), 1);
        int first = reader.read();
        if (first != -1 && first != '﻿') {
            reader.unread(first);
        }
        return reader;
    }
}
