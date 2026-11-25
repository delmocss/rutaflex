package com.rutaflex.api.stop;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rutaflex.api.AbstractApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Los fallos inesperados tambien salen como problem+json, se registran y no revelan detalles. */
class UnexpectedErrorsTest extends AbstractApiTest {

    @MockitoBean
    StopRepository repository;

    @Test
    void aBugIsA500ProblemWithoutInternalDetails() throws Exception {
        given(repository.findByDate(any())).willThrow(new IllegalStateException("secret internal detail"));

        mvc.perform(get("/api/stops").param("date", "2026-12-14"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Internal server error"))
                .andExpect(content().string(not(containsString("secret"))));
    }

    @Test
    void aDatabaseOutageIsA503Problem() throws Exception {
        given(repository.findByDate(any()))
                .willThrow(new CannotGetJdbcConnectionException("db down", new java.sql.SQLException("host:5432 refused")));

        mvc.perform(get("/api/stops").param("date", "2026-12-14"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().string(not(containsString("5432"))));
    }
}
