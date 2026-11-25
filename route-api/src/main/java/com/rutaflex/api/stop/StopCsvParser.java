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
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * Lee un CSV de paradas y lo valida entero antes de devolver nada: si una sola fila es
 * invalida no se importa ninguna. Formato: UTF-8 estricto (con o sin BOM; bytes que no sean UTF-8 se
 * rechazan en vez de guardar caracteres corruptos), separador coma, decimales con punto, cabecera
 * obligatoria (el orden y las mayusculas dan igual): {@code address, lat, lon, parcels} y
 * opcionalmente {@code window_start, window_end} (HH:mm). Los errores citan la linea fisica del
 * fichero (la cabecera es la 1; las lineas en blanco cuentan).
 *
 * <p>No cierra el {@code InputStream}: es responsabilidad de quien lo abre.
 */
@Component
class StopCsvParser {

    private static final int MAX_REPORTED_ERRORS = 25;
    private static final Set<String> REQUIRED = Set.of("address", "lat", "lon", "parcels");

    /** Decimal estricto: sin exponente, sin sufijos (40.2f), sin hexadecimales, sin NaN ni Infinity. */
    private static final Pattern DECIMAL = Pattern.compile("[+-]?\\d{1,3}(?:\\.\\d{1,15})?");

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreHeaderCase(true)
            .setIgnoreEmptyLines(true)
            .setAllowDuplicateHeaderNames(false)
            // Excel suele dejar una coma final en la cabecera ("a,b,c,"): una columna sin nombre no es un error.
            .setAllowMissingColumnNames(true)
            .setTrim(true)
            .get();

    private final Validator validator;
    private final ImportProperties properties;

    StopCsvParser(Validator validator, ImportProperties properties) {
        this.validator = validator;
        this.properties = properties;
    }

    List<StopRequest> parse(InputStream input, LocalDate deliveryDate) {
        try (PushbackReader reader = utf8Reader(input); CSVParser csv = FORMAT.parse(reader)) {
            requireColumns(csv.getHeaderNames());

            List<StopRequest> stops = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            for (CSVRecord record : csv) {
                if (record.getRecordNumber() > properties.maxRows()) {
                    throw new CsvImportException("The file has more than " + properties.maxRows() + " rows", List.of());
                }
                long line = csv.getCurrentLineNumber();
                StopRequest stop = toRequest(record, deliveryDate, line, errors);
                if (stop != null) {
                    validate(stop, line, errors);
                    stops.add(stop);
                }
            }

            if (stops.isEmpty() && errors.isEmpty()) {
                throw new CsvImportException("The file has no rows", List.of());
            }
            if (!errors.isEmpty()) {
                throw new CsvImportException("The CSV is not valid, nothing was imported", truncate(errors));
            }
            return stops;
        } catch (IllegalArgumentException e) {
            // commons-csv lo lanza al leer la cabecera (nombres de columna duplicados).
            throw new CsvImportException("The header is not valid: column names must be unique", List.of());
        } catch (IOException | UncheckedIOException e) {
            if (isEncodingError(e)) {
                throw new CsvImportException("The file is not valid UTF-8: save it as 'CSV UTF-8'", List.of());
            }
            throw new CsvImportException("The file is not a well-formed CSV (check quotes and separators)", List.of());
        }
    }

    private static void requireColumns(List<String> header) {
        List<String> lower = header.stream()
                .filter(Objects::nonNull)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();
        List<String> missing = REQUIRED.stream().filter(column -> !lower.contains(column)).sorted().toList();
        if (!missing.isEmpty()) {
            throw new CsvImportException(
                    "Missing required column(s): " + String.join(", ", missing),
                    List.of("Expected header: address,lat,lon,parcels[,window_start,window_end]"));
        }
    }

    private StopRequest toRequest(CSVRecord record, LocalDate deliveryDate, long line, List<String> errors) {
        int before = errors.size();

        Double lat = decimal(record, "lat", line, errors);
        Double lon = decimal(record, "lon", line, errors);
        Integer parcels = integer(record, "parcels", line, errors);
        LocalTime start = time(record, "window_start", line, errors);
        LocalTime end = time(record, "window_end", line, errors);

        if (errors.size() > before) {
            return null;
        }
        return new StopRequest(deliveryDate, text(record, "address"), new GeoPoint(lat, lon), parcels, start, end);
    }

    private void validate(StopRequest stop, long line, List<String> errors) {
        for (ConstraintViolation<StopRequest> violation : validator.validate(stop)) {
            errors.add("line " + line + ": " + column(violation.getPropertyPath().toString()) + " " + violation.getMessage());
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

    private static Double decimal(CSVRecord record, String column, long line, List<String> errors) {
        String value = text(record, column);
        if (!DECIMAL.matcher(value).matches()) {
            errors.add("line " + line + ": " + column + " is not a number (use a dot as the decimal separator)");
            return null;
        }
        return Double.valueOf(value);
    }

    private static Integer integer(CSVRecord record, String column, long line, List<String> errors) {
        try {
            return Integer.valueOf(text(record, column));
        } catch (NumberFormatException e) {
            errors.add("line " + line + ": " + column + " is not a whole number");
            return null;
        }
    }

    private static LocalTime time(CSVRecord record, String column, long line, List<String> errors) {
        String value = text(record, column);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException e) {
            errors.add("line " + line + ": " + column + " must be a time as HH:mm");
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

    /**
     * UTF-8 que falla ante bytes invalidos (el decodificador por defecto los sustituye en silencio por
     * U+FFFD y un CSV de Excel guardado como ANSI se importaria con "Estaci�n"). Descarta el BOM inicial.
     */
    private static PushbackReader utf8Reader(InputStream input) throws IOException {
        InputStreamReader decoded = new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT));
        PushbackReader reader = new PushbackReader(decoded, 1);
        int first = reader.read();
        if (first != -1 && first != '﻿') {
            reader.unread(first);
        }
        return reader;
    }

    private static boolean isEncodingError(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof CharacterCodingException) {
                return true;
            }
        }
        return false;
    }
}
