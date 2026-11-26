package com.rutaflex.api.job;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
class KafkaConfig {

    /**
     * Si guardar un resultado falla (por ejemplo, la base de datos está caída) se reintenta durante un minuto
     * con pausas crecientes (1 s, 2 s, 4 s... hasta 15 s). Si aun así no se puede, se registra y se sigue: el
     * trabajo lo acabará marcando FAILED el vigilante de trabajos atascados ({@link JobReaper}).
     */
    @Bean
    CommonErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(15_000L);
        backOff.setMaxElapsedTime(60_000L);
        return new DefaultErrorHandler(backOff);
    }
}
