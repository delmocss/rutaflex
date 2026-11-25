package com.rutaflex.api.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.rutaflex.api.AbstractApiTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class WarehouseApiTest extends AbstractApiTest {

    static final String VALID = """
            {"name": "Almacen Valdemoro", "address": "Pol. Ind. Los Llanos, Valdemoro",
             "location": {"lat": 40.1935, "lon": -3.6650}}""";

    @Test
    void createsAndReadsBackTheSamePoint() throws Exception {
        String body = mvc.perform(post("/api/warehouses").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/warehouses/")))
                .andExpect(jsonPath("$.name").value("Almacen Valdemoro"))
                .andExpect(jsonPath("$.location.lat").value(40.1935))
                .andExpect(jsonPath("$.location.lon").value(-3.6650))
                .andExpect(jsonPath("$.createdAt").exists())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        mvc.perform(get("/api/warehouses/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location.lat").value(40.1935))
                .andExpect(jsonPath("$.location.lon").value(-3.6650));

        // Se guarda como geography: PostGIS puede medirla.
        assertThat(jdbc.queryForObject(
                "SELECT ST_DWithin(location, ST_SetSRID(ST_MakePoint(-3.6650, 40.1935), 4326)::geography, 1) FROM warehouse",
                Boolean.class)).isTrue();
    }

    @Test
    void listsOrderedByName() throws Exception {
        create("Zeta", 40.2, -3.7);
        create("Alfa", 40.3, -3.7);

        mvc.perform(get("/api/warehouses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("Alfa"))
                .andExpect(jsonPath("$[1].name").value("Zeta"));
    }

    @Test
    void rejectsInvalidBodyWithFieldErrors() throws Exception {
        mvc.perform(post("/api/warehouses").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": " ", "address": "x", "location": {"lat": 91, "lon": -3.6}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors['location.lat']").exists());

        mvc.perform(post("/api/warehouses").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.location").exists());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM warehouse", Integer.class)).isZero();
    }

    @Test
    void rejectsMalformedJsonAndMalformedIds() throws Exception {
        mvc.perform(post("/api/warehouses").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/warehouses/not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void unknownWarehouseIs404() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(get("/api/warehouses/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString(id.toString())));
        mvc.perform(put("/api/warehouses/" + id).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/warehouses/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void updatesAndDeletes() throws Exception {
        String id = create("Original", 40.2, -3.7);

        mvc.perform(put("/api/warehouses/" + id).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Almacen Valdemoro"))
                .andExpect(jsonPath("$.location.lat").value(40.1935));

        mvc.perform(delete("/api/warehouses/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/warehouses/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void cannotDeleteAWarehouseThatHasOptimizationJobs() throws Exception {
        String id = create("Con trabajos", 40.2, -3.7);
        jdbc.update("INSERT INTO optimization_job (warehouse_id, delivery_date) VALUES (?::uuid, '2026-12-14')", id);

        mvc.perform(delete("/api/warehouses/" + id))
                .andExpect(status().isConflict())
                // Sin filtrar mensajes internos de la base de datos.
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(containsString("constraint"))));
    }

    private String create(String name, double lat, double lon) throws Exception {
        String body = mvc.perform(post("/api/warehouses").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "%s", "address": "Calle de prueba", "location": {"lat": %s, "lon": %s}}"""
                        .formatted(name, lat, lon)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }
}
