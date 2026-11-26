package com.rutaflex.api.job;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
class KafkaConfig {

    /**
     * Si guardar un resultado falla (por ejemplo, la base de datos está caída un momento) se reintenta cada
     * segundo, cinco veces, antes de rendirse y registrar el error.
     */
    @Bean
    CommonErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(1000L, 5L));
    }
}
