package com.rutaflex.api.stop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.rutaflex.api.AbstractApiTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class StopApiTest extends AbstractApiTest {

    static final String WITH_WINDOW = """
            {"deliveryDate": "2026-12-14", "address": "Calle Mayor 1, Pinto",
             "location": {"lat": 40.2428, "lon": -3.6997}, "parcels": 3,
             "windowStart": "09:00", "windowEnd": "11:00"}""";

    static final String WITHOUT_WINDOW = """
            {"deliveryDate": "2026-12-14", "address": "Calle Real 5, Getafe",
             "location": {"lat": 40.3057, "lon": -3.7329}, "parcels": 1}""";

    @Test
    void createsStopsWithAndWithoutTimeWindow() throws Exception {
        mvc.perform(post("/api/stops").contentType(MediaType.APPLICATION_JSON).content(WITH_WINDOW))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.location.lat").value(40.2428))
                .andExpect(jsonPath("$.windowStart").value("09:00:00"))
                .andExpect(jsonPath("$.windowEnd").value("11:00:00"));

        mvc.perform(post("/api/stops").contentType(MediaType.APPLICATION_JSON).content(WITHOUT_WINDOW))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.windowStart").doesNotExist())
                .andExpect(jsonPath("$.windowEnd").doesNotExist());

        // La ubicacion es geography: medible en metros desde SQL.
        assertThat(jdbc.queryForObject("""
                SELECT ST_Distance(a.location, b.location) FROM delivery_stop a, delivery_stop b
                WHERE a.address LIKE '%Pinto' AND b.address LIKE '%Getafe'""", Double.class))
                .isBetween(7_000.0, 10_000.0);
    }

    @Test
    void listsOnlyTheRequestedDay() throws Exception {
        mvc.perform(post("/api/stops").contentType(MediaType.APPLICATION_JSON).content(WITH_WINDOW));
        mvc.perform(post("/api/stops").contentType(MediaType.APPLICATION_JSON)
                .content(WITHOUT_WINDOW.replace("2026-12-14", "2026-12-15")));

        mvc.perform(get("/api/stops").param("date", "2026-12-14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].address").value("Calle Mayor 1, Pinto"));
        mvc.perform(get("/api/stops").param("date", "2030-01-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void dateIsRequiredAndMustBeValid() throws Exception {
        mvc.perform(get("/api/stops")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/stops").param("date", "14/12/2026")).andExpect(status().isBadRequest());
    }

    @Test
    void summarisesTheDaysThatHaveStops() throws Exception {
        mvc.perform(post("/api/stops").contentType(MediaType.APPLICATION_JSON).content(WITH_WINDOW));
        mvc.perform(post("/api/stops").contentType(MediaType.APPLICATION_JSON).content(WITHOUT_WINDOW));
        mvc.perform(post("/api/stops").contentType(MediaType.APPLICATION_JSON)
                .content(WITHOUT_WINDOW.replace("2026-12-14", "2026-12-15")));

        mvc.perform(get("/api/stops/dates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].date").value("2026-12-14"))
                .andExpect(jsonPath("$[0].stops").value(2))
                .andExpect(jsonPath("$[0].parcels").value(4))
                .andExpect(jsonPath("$[1].date").value("2026-12-15"))
                .andExpect(jsonPath("$[1].stops").value(1));
    }

    @Test
    void rejectsInvalidStops() throws Exception {
        // Franja a medias
        mvc.perform(post("/api/stops").contentType(MediaType.APPLICATION_JSON)
                        .content(WITH_WINDOW.replace(", \"windowEnd\": \"11:00\"", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.windowValid").exists());
        // Franja al reves
        mvc.perform(post("/api/stops").contentType(MediaType.APPLICATION_JSON)
                        .content(WITH_WINDOW.replace("\"09:00\"", "\"12:00\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.windowValid").exists());
        // Bultos cero, direccion vacia, coordenadas fuera de rango y sin fecha
        mvc.perform(post("/api/stops").contentType(MediaType.APPLICATION_JSON).content("""
                        {"address": "", "location": {"lat": 40.2, "lon": 181}, "parcels": 0}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.address").exists())
                .andExpect(jsonPath("$.errors['location.lon']").exists())
                .andExpect(jsonPath("$.errors.parcels").exists())
                .andExpect(jsonPath("$.errors.deliveryDate").exists());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_stop", Integer.class)).isZero();
    }

    @Test
    void aNulCharacterIsABadRequestNotAConflict() throws Exception {
        // Pasa la validacion de Bean Validation pero PostgreSQL no puede guardar el byte 0 en un texto
        // (SQLSTATE 22021): es un dato invalido del cliente, no un conflicto con otros datos.
        mvc.perform(post("/api/stops").contentType(MediaType.APPLICATION_JSON).content(
                        WITHOUT_WINDOW.replace("Calle Real 5, Getafe", "Calle\\u0000Real")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The request contains a value the database cannot store"));
    }

    @Test
    void updatesAndDeletesOneStop() throws Exception {
        String body = mvc.perform(post("/api/stops").contentType(MediaType.APPLICATION_JSON).content(WITH_WINDOW))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        mvc.perform(put("/api/stops/" + id).contentType(MediaType.APPLICATION_JSON).content(WITHOUT_WINDOW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("Calle Real 5, Getafe"))
                .andExpect(jsonPath("$.windowStart").doesNotExist());

        mvc.perform(delete("/api/stops/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/stops/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void unknownStopIs404() throws Exception {
        UUID unknown = UUID.randomUUID();
        mvc.perform(get("/api/stops/" + unknown)).andExpect(status().isNotFound());
        mvc.perform(put("/api/stops/" + unknown).contentType(MediaType.APPLICATION_JSON).content(WITH_WINDOW))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/stops/" + unknown)).andExpect(status().isNotFound());
    }

    @Test
    void deletesAWholeDayAndLeavesTheRest() throws Exception {
        mvc.perform(post("/api/stops").contentType(MediaType.APPLICATION_JSON).content(WITH_WINDOW));
        mvc.perform(post("/api/stops").contentType(MediaType.APPLICATION_JSON).content(WITHOUT_WINDOW));
        mvc.perform(post("/api/stops").contentType(MediaType.APPLICATION_JSON)
                .content(WITHOUT_WINDOW.replace("2026-12-14", "2026-12-15")));

        mvc.perform(delete("/api/stops").param("date", "2026-12-14")).andExpect(status().isNoContent());

        assertThat(jdbc.queryForList("SELECT delivery_date::text FROM delivery_stop", String.class))
                .containsExactly("2026-12-15");
        // Idempotente: borrar un dia sin paradas no es un error.
        mvc.perform(delete("/api/stops").param("date", "2026-12-14")).andExpect(status().isNoContent());
    }
}
