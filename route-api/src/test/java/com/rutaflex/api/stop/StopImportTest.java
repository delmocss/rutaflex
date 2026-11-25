package com.rutaflex.api.stop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rutaflex.api.AbstractApiTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

@TestPropertySource(properties = "rutaflex.import.max-rows=5")
class StopImportTest extends AbstractApiTest {

    static final String DAY = "2026-12-14";
    static final String HEADER = "address,lat,lon,parcels\n";

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

        assertThat(count("SELECT count(*) FROM delivery_stop WHERE delivery_date = '2026-12-14'")).isEqualTo(2);
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
        post("﻿PARCELS,Lon,ADDRESS,Lat\n2,-3.6742,Calle Uno,40.1908\n", DAY, false)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imported").value(1));
    }

    @Test
    void aTrailingCommaInTheHeaderFromExcelIsFine() throws Exception {
        post("address,lat,lon,parcels,\nCalle Uno,40.1908,-3.6742,2,\n", DAY, false)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imported").value(1));
    }

    @Test
    void keepsTheOrderOfTheFile() throws Exception {
        post(HEADER + "E,40.2,-3.7,1\nD,40.2,-3.7,1\nC,40.2,-3.7,1\nB,40.2,-3.7,1\nA,40.2,-3.7,1\n", DAY, false)
                .andExpect(status().isCreated());

        // El orden de llegada es la base de la comparacion "sin optimizar": no puede depender del azar.
        mvc.perform(get("/api/stops").param("date", DAY))
                .andExpect(jsonPath("$[0].address").value("E"))
                .andExpect(jsonPath("$[1].address").value("D"))
                .andExpect(jsonPath("$[2].address").value("C"))
                .andExpect(jsonPath("$[3].address").value("B"))
                .andExpect(jsonPath("$[4].address").value("A"));
    }

    @Test
    void rejectsTheWholeFileIfOneRowIsInvalidAndReportsEveryProblemWithItsLine() throws Exception {
        String csv = """
                address,lat,lon,parcels,window_start,window_end
                Calle buena,40.2,-3.7,1,,
                Calle mala 1,40.2,-3.7,0,,
                Calle mala 2,95,-3.7,1,,
                Calle mala 3,"40,2",-3.7,1,,
                Calle mala 4,40.2,-3.7,1,12:00,
                """;

        post(csv, DAY, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The CSV is not valid, nothing was imported"))
                .andExpect(jsonPath("$.errors[*]", hasItem("line 3: parcels must be greater than or equal to 1")))
                .andExpect(jsonPath("$.errors[*]", hasItem(startsWith("line 4: lat"))))
                .andExpect(jsonPath("$.errors[*]", hasItem("line 5: lat is not a number (use a dot as the decimal separator)")))
                .andExpect(jsonPath("$.errors[*]", hasItem(startsWith("line 6: window_start/window_end"))));

        // La fila buena tampoco se importo: todo o nada.
        assertThat(count("SELECT count(*) FROM delivery_stop")).isZero();
    }

    @Test
    void errorLinesAreThePhysicalLinesEvenWithBlankLines() throws Exception {
        post("address,lat,lon,parcels\n\nCalle,40.2,-3.7,1\n\n\nMala,40.2,-3.7,0\n", DAY, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("line 6: parcels must be greater than or equal to 1"));
    }

    @Test
    void validationMessagesAreAlwaysInEnglishWhateverTheClientLanguage() throws Exception {
        mvc.perform(multipart("/api/stops/import")
                        .file(file(HEADER + "Calle,40.2,-3.7,0\n"))
                        .param("date", DAY)
                        .header("Accept-Language", "es-ES"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("line 2: parcels must be greater than or equal to 1"));
    }

    @Test
    void onlyPlainDecimalCoordinatesAreAccepted() throws Exception {
        String csv = HEADER + "A,NaN,-3.7,1\nB,Infinity,-3.7,1\nC,0x1p3,-3.7,1\nD,40.2f,-3.7,1\nE,1e1,-3.7,1\n";

        post(csv, DAY, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("line 2: lat is not a number (use a dot as the decimal separator)"))
                .andExpect(jsonPath("$.errors[1]").value("line 3: lat is not a number (use a dot as the decimal separator)"))
                .andExpect(jsonPath("$.errors[2]").value("line 4: lat is not a number (use a dot as the decimal separator)"))
                .andExpect(jsonPath("$.errors[3]").value("line 5: lat is not a number (use a dot as the decimal separator)"))
                .andExpect(jsonPath("$.errors[4]").value("line 6: lat is not a number (use a dot as the decimal separator)"));
        assertThat(count("SELECT count(*) FROM delivery_stop")).isZero();
    }

    @Test
    void rejectsBytesThatAreNotUtf8InsteadOfStoringGarbage() throws Exception {
        // "Calle de la Estación" guardado como ANSI (Windows-1252), como hace Excel en espanol.
        byte[] ansi = (HEADER + "Calle de la Estación,40.2,-3.7,1\n").getBytes(StandardCharsets.ISO_8859_1);

        mvc.perform(multipart("/api/stops/import")
                        .file(new MockMultipartFile("file", "stops.csv", "text/csv", ansi))
                        .param("date", DAY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("not valid UTF-8")));
        assertThat(count("SELECT count(*) FROM delivery_stop")).isZero();
    }

    @Test
    void acceptsRealUtf8Accents() throws Exception {
        post(HEADER + "Calle de la Estación Ñandú,40.2,-3.7,1\n", DAY, false).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("SELECT address FROM delivery_stop", String.class))
                .isEqualTo("Calle de la Estación Ñandú");
    }

    @Test
    void rejectsAMissingRequiredColumn() throws Exception {
        post("address,lat,parcels\nCalle,40.2,1\n", DAY, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Missing required column(s): lon"));
    }

    @Test
    void rejectsDuplicateColumns() throws Exception {
        post("address,lat,lat,lon,parcels\nCalle,40.2,40.2,-3.7,1\n", DAY, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("column names must be unique")));
    }

    @Test
    void rejectsEmptyOrHeaderOnlyFiles() throws Exception {
        post("", DAY, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("Missing required column")));
        post(HEADER, DAY, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The file has no rows"));
    }

    @Test
    void rejectsMoreRowsThanTheLimit() throws Exception {
        String rows = String.join("\n", Collections.nCopies(6, "Calle,40.2,-3.7,1"));
        post(HEADER + rows + "\n", DAY, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The file has more than 5 rows"));
        assertThat(count("SELECT count(*) FROM delivery_stop")).isZero();
    }

    @Test
    void rejectsMalformedCsv() throws Exception {
        post(HEADER + "\"sin cerrar,40.2,-3.7,1\n", DAY, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("not a well-formed CSV")));
    }

    @Test
    void requiresTheDateAndTheFile() throws Exception {
        mvc.perform(multipart("/api/stops/import").file(file(HEADER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("'date'")));
        mvc.perform(multipart("/api/stops/import").param("date", DAY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("'file'")));
    }

    @Test
    void importingAgainWithoutReplaceAppends() throws Exception {
        String csv = HEADER + "Calle,40.2,-3.7,1\n";
        post(csv, DAY, false).andExpect(status().isCreated());
        post(csv, DAY, false).andExpect(status().isCreated());

        assertThat(count("SELECT count(*) FROM delivery_stop")).isEqualTo(2);
    }

    @Test
    void replaceSwapsTheDay() throws Exception {
        post(HEADER + "Vieja 1,40.2,-3.7,1\nVieja 2,40.2,-3.7,1\n", DAY, false);
        post(HEADER + "Otra,40.2,-3.7,1\n", "2026-12-15", false);

        post(HEADER + "Nueva,40.3,-3.7,2\n", DAY, true)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.replaced").value(2));

        assertThat(jdbc.queryForList("SELECT address FROM delivery_stop ORDER BY address", String.class))
                .containsExactly("Nueva", "Otra");
    }

    @Test
    void anInvalidCsvWithReplaceLeavesTheDayUntouched() throws Exception {
        post(HEADER + "Vieja 1,40.2,-3.7,1\n", DAY, false);

        post(HEADER + "Mala,999,-3.7,1\n", DAY, true).andExpect(status().isBadRequest());

        assertThat(jdbc.queryForList("SELECT address FROM delivery_stop", String.class)).containsExactly("Vieja 1");
    }

    /**
     * El rollback real: la fila pasa el parser y la validacion pero la base de datos rechaza el NUL
     * (SQLSTATE 22021) DESPUES de haber borrado el dia. Sin transaccion el dia quedaria vacio.
     */
    @Test
    void aDatabaseFailureAfterTheDeleteRollsEverythingBack() throws Exception {
        post(HEADER + "Vieja 1,40.2,-3.7,1\nVieja 2,40.2,-3.7,1\n", DAY, false);

        post(HEADER + "Buena,40.2,-3.7,1\nMa\0la,40.2,-3.7,1\n", DAY, true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The request contains a value the database cannot store"));

        assertThat(jdbc.queryForList("SELECT address FROM delivery_stop ORDER BY address", String.class))
                .containsExactly("Vieja 1", "Vieja 2");
    }

    /** Dos reemplazos simultaneos del mismo dia deben dejar UNA copia, no dos. */
    @Test
    void concurrentReplacesOfTheSameDayDoNotDuplicateStops() throws Exception {
        String first = HEADER + "A1,40.2,-3.7,1\nA2,40.2,-3.7,1\nA3,40.2,-3.7,1\n";
        String second = HEADER + "B1,40.3,-3.7,1\nB2,40.3,-3.7,1\nB3,40.3,-3.7,1\n";
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 15; round++) {
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Integer>> results = new ArrayList<>();
                for (String csv : List.of(first, second)) {
                    Callable<Integer> request = () -> {
                        go.await();
                        return post(csv, DAY, true).andReturn().getResponse().getStatus();
                    };
                    results.add(pool.submit(request));
                }
                go.countDown();
                for (Future<Integer> result : results) {
                    assertThat(result.get()).isEqualTo(201);
                }

                List<String> addresses = jdbc.queryForList("SELECT address FROM delivery_stop ORDER BY address", String.class);
                assertThat(addresses).hasSize(3);
                assertThat(addresses.get(0).charAt(0)).isIn('A', 'B');
                // Las tres son de la misma importacion, nunca mezcladas.
                assertThat(addresses).allMatch(address -> address.charAt(0) == addresses.get(0).charAt(0));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private ResultActions post(String csv, String date, boolean replace) throws Exception {
        return mvc.perform(multipart("/api/stops/import")
                .file(file(csv)).param("date", date).param("replace", String.valueOf(replace)));
    }

    private static MockMultipartFile file(String csv) {
        return new MockMultipartFile("file", "stops.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
