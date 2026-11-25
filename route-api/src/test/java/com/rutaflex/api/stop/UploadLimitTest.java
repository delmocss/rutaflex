package com.rutaflex.api.stop;

import static org.assertj.core.api.Assertions.assertThat;

import com.rutaflex.api.AbstractApiTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * El limite de 1 MB lo aplica el contenedor de servlets, asi que hace falta un servidor real
 * (MockMvc no pasa por el): se comprueba que responde 413 como problem+json y no guarda nada.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UploadLimitTest extends AbstractApiTest {

    @Autowired
    TestRestTemplate http;

    @Test
    void anUploadOverOneMegabyteIs413() {
        byte[] big = ("address,lat,lon,parcels\n" + "Calle,40.2,-3.7,1\n".repeat(80_000)).getBytes(StandardCharsets.UTF_8);
        assertThat(big.length).isGreaterThan(1024 * 1024);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(big) {
            @Override
            public String getFilename() {
                return "stops.csv";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        var response = http.postForEntity("/api/stops/import?date=2026-12-14", new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_stop", Integer.class)).isZero();
    }
}
