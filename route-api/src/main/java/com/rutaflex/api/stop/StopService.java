package com.rutaflex.api.stop;

import com.rutaflex.api.common.NotFoundException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class StopService {

    private final StopRepository repository;

    StopService(StopRepository repository) {
        this.repository = repository;
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
}
