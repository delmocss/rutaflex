package com.rutaflex.api.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.rutaflex.api.AbstractApiTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class VehicleApiTest extends AbstractApiTest {

    static final String VALID = """
            {"name": "Furgoneta 1", "capacityParcels": 40, "shiftStart": "08:00", "shiftEnd": "15:00"}""";

    String warehouseId;

    @BeforeEach
    void createWarehouse() throws Exception {
        String body = mvc.perform(post("/api/warehouses").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Almacen", "address": "Valdemoro", "location": {"lat": 40.1935, "lon": -3.6650}}"""))
                .andReturn().getResponse().getContentAsString();
        warehouseId = JsonPath.read(body, "$.id");
    }

    @Test
    void createsAndReadsAVehicle() throws Exception {
        String body = mvc.perform(post("/api/warehouses/" + warehouseId + "/vehicles")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/vehicles/")))
                .andExpect(jsonPath("$.warehouseId").value(warehouseId))
                .andExpect(jsonPath("$.capacityParcels").value(40))
                .andExpect(jsonPath("$.shiftStart").value("08:00:00"))
                .andExpect(jsonPath("$.shiftEnd").value("15:00:00"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        mvc.perform(get("/api/vehicles/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Furgoneta 1"));
        mvc.perform(get("/api/warehouses/" + warehouseId + "/vehicles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void rejectsInvalidVehicles() throws Exception {
        // Jornada al reves
        mvc.perform(post("/api/warehouses/" + warehouseId + "/vehicles").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "V", "capacityParcels": 10, "shiftStart": "15:00", "shiftEnd": "08:00"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.shiftOrdered").value("shiftEnd must be after shiftStart"));
        // Capacidad cero, nombre vacio y jornada sin informar
        mvc.perform(post("/api/warehouses/" + warehouseId + "/vehicles").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "", "capacityParcels": 0}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.capacityParcels").exists())
                .andExpect(jsonPath("$.errors.shiftStart").exists())
                .andExpect(jsonPath("$.errors.shiftEnd").exists());
        // Hora con formato invalido
        mvc.perform(post("/api/warehouses/" + warehouseId + "/vehicles").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "V", "capacityParcels": 10, "shiftStart": "ocho", "shiftEnd": "15:00"}"""))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicle", Integer.class)).isZero();
    }

    @Test
    void vehicleNamesAreUniquePerWarehouse() throws Exception {
        mvc.perform(post("/api/warehouses/" + warehouseId + "/vehicles")
                .contentType(MediaType.APPLICATION_JSON).content(VALID)).andExpect(status().isCreated());

        mvc.perform(post("/api/warehouses/" + warehouseId + "/vehicles")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isConflict());
    }

    @Test
    void unknownWarehouseOrVehicleIs404() throws Exception {
        UUID unknown = UUID.randomUUID();
        mvc.perform(post("/api/warehouses/" + unknown + "/vehicles")
                .contentType(MediaType.APPLICATION_JSON).content(VALID)).andExpect(status().isNotFound());
        mvc.perform(get("/api/warehouses/" + unknown + "/vehicles")).andExpect(status().isNotFound());
        mvc.perform(get("/api/vehicles/" + unknown)).andExpect(status().isNotFound());
        mvc.perform(put("/api/vehicles/" + unknown).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/vehicles/" + unknown)).andExpect(status().isNotFound());
    }

    @Test
    void updatesAndDeletes() throws Exception {
        String body = mvc.perform(post("/api/warehouses/" + warehouseId + "/vehicles")
                .contentType(MediaType.APPLICATION_JSON).content(VALID)).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        mvc.perform(put("/api/vehicles/" + id).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Furgoneta 1", "capacityParcels": 55, "shiftStart": "07:30", "shiftEnd": "16:00"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacityParcels").value(55))
                .andExpect(jsonPath("$.shiftStart").value("07:30:00"));

        mvc.perform(delete("/api/vehicles/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/vehicles/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void deletingTheWarehouseRemovesItsVehicles() throws Exception {
        mvc.perform(post("/api/warehouses/" + warehouseId + "/vehicles")
                .contentType(MediaType.APPLICATION_JSON).content(VALID)).andExpect(status().isCreated());

        mvc.perform(delete("/api/warehouses/" + warehouseId)).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicle", Integer.class)).isZero();
    }
}
