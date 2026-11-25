package com.rutaflex.api.stop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rutaflex.api.AbstractApiTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

@TestPropertySource(properties = "rutaflex.import.max-rows=5")
class StopImportTest extends AbstractApiTest {

    static final String DAY = "2026-12-14";

    @Test
    void importsAValidCsvWithAllColumns() throws Exception {
        String csv = """
                address,lat,lon,parcels,window_start,window_end
                "Calle Mayor 1, Pinto",40.2428,-3.6997,3,09:00,11:00
                Calle Real 5 Getafe,40.3057,-3.7329,1,,
                """;

        post(csv, DAY, false)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.date").value(DAY))
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.replaced").value(0));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_stop WHERE delivery_date = '2026-12-14'", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT window_start::text FROM delivery_stop WHERE address = 'Calle Mayor 1, Pinto'", String.class))
                .isEqualTo("09:00:00");
        assertThat(jdbc.queryForObject(
                "SELECT window_start IS NULL FROM delivery_stop WHERE address LIKE 'Calle Real%'", Boolean.class)).isTrue();
        // Quedan como geography.
        assertThat(jdbc.queryForObject(
                "SELECT ST_Y(location::geometry) FROM delivery_stop WHERE address LIKE 'Calle Real%'", Double.class))
                .isEqualTo(40.3057);
    }

    @Test
    void windowColumnsAreOptionalAndOrderAndCaseDoNotMatter() throws Exception {
        String csv = "﻿PARCELS,Lon,ADDRESS,Lat\n2,-3.6742,Calle Uno,40.1908\n";

        post(csv, DAY, false)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imported").value(1));
    }

    @Test
    void rejectsTheWholeFileIfOneRowIsInvalidAndReportsEveryProblemWithItsRow() throws Exception {
        String csv = """
                address,lat,lon,parcels,window_start,window_end
                Calle buena,40.2,-3.7,1,,
                Calle mala 1,40.2,-3.7,0,,
                Calle mala 2,95,-3.7,1,,
                Calle mala 4,"40,2",-3.7,1,,
                Calle mala 5,40.2,-3.7,1,12:00,
                """;

        post(csv, DAY, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The CSV is not valid, nothing was imported"))
                .andExpect(jsonPath("$.errors[*]", hasItem(startsWith("row 2: parcels"))))
                .andExpect(jsonPath("$.errors[*]", hasItem(startsWith("row 3: lat"))))
                .andExpect(jsonPath("$.errors[*]", hasItem("row 4: lat is not a number (use a dot as the decimal separator)")))
                .andExpect(jsonPath("$.errors[*]", hasItem(startsWith("row 5: window_start/window_end"))));

        // La fila buena tampoco se importo: todo o nada.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_stop", Integer.class)).isZero();
    }

    @Test
    void validationMessagesAreAlwaysInEnglishWhateverTheClientLanguage() throws Exception {
        mvc.perform(multipart("/api/stops/import")
                        .file(file("address,lat,lon,parcels\nCalle,40.2,-3.7,0\n"))
                        .param("date", DAY)
                        .header("Accept-Language", "es-ES"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("row 1: parcels must be greater than or equal to 1"));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/stops")
                        .header("Accept-Language", "es-ES")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"deliveryDate\": \"2026-12-14\", \"address\": \"x\", "
                                + "\"location\": {\"lat\": 40.2, \"lon\": -3.7}, \"parcels\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.parcels").value("must be greater than or equal to 1"));
    }

    @Test
    void rejectsNonFiniteCoordinates() throws Exception {
        post("address,lat,lon,parcels\nA,NaN,-3.7,1\nB,Infinity,-3.7,1\n", DAY, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*]", hasItem(startsWith("row 1: lat"))))
                .andExpect(jsonPath("$.errors[*]", hasItem(startsWith("row 2: lat"))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_stop", Integer.class)).isZero();
    }

    @Test
    void rejectsAMissingRequiredColumn() throws Exception {
        post("address,lat,parcels\nCalle,40.2,1\n", DAY, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Missing required column(s): lon"));
    }

    @Test
    void rejectsEmptyOrHeaderOnlyFiles() throws Exception {
        post("", DAY, false).andExpect(status().isBadRequest());
        post("address,lat,lon,parcels\n", DAY, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The file has no rows"));
    }

    @Test
    void rejectsMoreRowsThanTheLimit() throws Exception {
        String rows = String.join("\n", java.util.Collections.nCopies(6, "Calle,40.2,-3.7,1"));
        post("address,lat,lon,parcels\n" + rows + "\n", DAY, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The file has more than 5 rows"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_stop", Integer.class)).isZero();
    }

    @Test
    void rejectsMalformedCsv() throws Exception {
        post("address,lat,lon,parcels\n\"sin cerrar,40.2,-3.7,1\n", DAY, false)
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresTheDateAndTheFile() throws Exception {
        mvc.perform(multipart("/api/stops/import").file(file("address,lat,lon,parcels\n")))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/stops/import").param("date", DAY)).andExpect(status().isBadRequest());
    }

    @Test
    void importingAgainWithoutReplaceAppends() throws Exception {
        String csv = "address,lat,lon,parcels\nCalle,40.2,-3.7,1\n";
        post(csv, DAY, false).andExpect(status().isCreated());
        post(csv, DAY, false).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_stop", Integer.class)).isEqualTo(2);
    }

    @Test
    void replaceSwapsTheDayAtomically() throws Exception {
        post("address,lat,lon,parcels\nVieja 1,40.2,-3.7,1\nVieja 2,40.2,-3.7,1\n", DAY, false);
        post("address,lat,lon,parcels\nOtra,40.2,-3.7,1\n", "2026-12-15", false);

        post("address,lat,lon,parcels\nNueva,40.3,-3.7,2\n", DAY, true)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.replaced").value(2));

        assertThat(jdbc.queryForList("SELECT address FROM delivery_stop ORDER BY address", String.class))
                .containsExactly("Nueva", "Otra");
    }

    @Test
    void anInvalidCsvWithReplaceLeavesTheDayUntouched() throws Exception {
        post("address,lat,lon,parcels\nVieja 1,40.2,-3.7,1\n", DAY, false);

        post("address,lat,lon,parcels\nMala,999,-3.7,1\n", DAY, true).andExpect(status().isBadRequest());

        assertThat(jdbc.queryForList("SELECT address FROM delivery_stop", String.class)).containsExactly("Vieja 1");
    }

    private ResultActions post(String csv, String date, boolean replace) throws Exception {
        return mvc.perform(multipart("/api/stops/import")
                .file(file(csv)).param("date", date).param("replace", String.valueOf(replace)));
    }

    private static MockMultipartFile file(String csv) {
        return new MockMultipartFile("file", "stops.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    }
}
