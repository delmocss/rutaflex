package com.rutaflex.api.stop;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Importacion de paradas desde CSV. El fichero se lee y se valida entero <b>antes</b> de abrir la
 * transaccion (este servicio no es transaccional): asi no se retiene una conexion de la base de datos
 * mientras se lee una subida lenta. La escritura, atomica, la hace {@link StopService#saveImported}.
 */
@Service
public class StopImportService {

    private final StopCsvParser parser;
    private final StopService stops;

    StopImportService(StopCsvParser parser, StopService stops) {
        this.parser = parser;
        this.stops = stops;
    }

    /** El llamador es quien abre y cierra {@code csv}. */
    public ImportResult importCsv(LocalDate date, InputStream csv, boolean replace) {
        List<StopRequest> parsed = parser.parse(csv, date);
        return stops.saveImported(date, parsed, replace);
    }
}
