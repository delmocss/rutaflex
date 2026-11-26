package com.rutaflex.api.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rutaflex.api.AbstractApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Qué pasa si el broker no confirma el mensaje: el trabajo no queda colgado en QUEUED y el cliente lo sabe. */
class JobEnqueueFailureTest extends AbstractApiTest {

    @MockitoBean
    OptimizationRequestPublisher publisher;

    @Test
    void ifTheMessageCannotBePublishedTheRequestIs503AndTheJobIsMarkedFailed() throws Exception {
        doThrow(new IllegalStateException("broker down")).when(publisher).publish(any());
        mvc.perform(post("/api/warehouses").contentType(MediaType.APPLICATION_JSON).content("""
                {"name": "A", "address": "B", "location": {"lat": 40.19, "lon": -3.65}}"""));
        String warehouse = jdbc.queryForObject("SELECT id::text FROM warehouse", String.class);
        mvc.perform(post("/api/warehouses/" + warehouse + "/vehicles").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"V\", \"capacityParcels\": 10, \"shiftStart\": \"08:00\", \"shiftEnd\": \"15:00\"}"));
        mvc.perform(post("/api/stops").contentType(MediaType.APPLICATION_JSON).content("""
                {"deliveryDate": "2026-12-14", "address": "S", "location": {"lat": 40.2, "lon": -3.7}, "parcels": 1}"""));

        mvc.perform(post("/api/optimizations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deliveryDate\": \"2026-12-14\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("The optimization could not be queued, try again shortly"));

        assertThat(jdbc.queryForObject("SELECT status FROM optimization_job", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM optimization_job", String.class))
                .contains("could not be queued");
        // Al quedar FAILED, el indice de trabajo activo no bloquea el siguiente intento.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM optimization_job WHERE status IN ('QUEUED','RUNNING')",
                Integer.class)).isZero();
    }
}
