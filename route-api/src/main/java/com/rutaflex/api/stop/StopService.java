package com.rutaflex.api.stop;

import com.rutaflex.api.common.NotFoundException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class StopService {

    private final StopRepository repository;
    private final StopCsvParser csvParser;

    StopService(StopRepository repository, StopCsvParser csvParser) {
        this.repository = repository;
        this.csvParser = csvParser;
    }

    public StopResponse create(StopRequest request) {
        return repository.insert(request);
    }

    @Transactional(readOnly = true)
    public List<StopResponse> listByDate(LocalDate date) {
        return repository.findByDate(date);
    }

    @Transactional(readOnly = true)
    public List<StopDateSummary> summarizeByDate() {
        return repository.summarizeByDate();
    }

    @Transactional(readOnly = true)
    public StopResponse get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Stop", id));
    }

    public StopResponse update(UUID id, StopRequest request) {
        return repository.update(id, request).orElseThrow(() -> new NotFoundException("Stop", id));
    }

    public void delete(UUID id) {
        if (!repository.deleteById(id)) {
            throw new NotFoundException("Stop", id);
        }
    }

    /** Borra todas las paradas de un dia (para volver a importar). Devuelve cuantas eran. */
    public int deleteByDate(LocalDate date) {
        return repository.deleteByDate(date);
    }

    /**
     * Importa un CSV de paradas para un dia. Todo o nada: el CSV se valida entero antes de tocar la
     * base de datos y, con {@code replace}, el borrado de las paradas anteriores y la insercion
     * ocurren en la misma transaccion (si algo falla, el dia queda como estaba).
     */
    public ImportResult importCsv(LocalDate date, InputStream csv, boolean replace) {
        List<StopRequest> stops = csvParser.parse(csv, date);
        int replaced = replace ? repository.deleteByDate(date) : 0;
        repository.insertAll(stops);
        return new ImportResult(date, stops.size(), replaced);
    }
}
