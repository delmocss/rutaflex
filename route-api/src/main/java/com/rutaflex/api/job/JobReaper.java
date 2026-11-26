package com.rutaflex.api.job;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cierra los trabajos que llevan demasiado tiempo sin terminar. Es la red de seguridad de todo el flujo: el
 * resultado puede perderse (el mensaje no llegó, la base de datos estuvo caída al guardarlo, el proceso murió
 * entre guardar y publicar...) y, como solo puede haber un trabajo activo por almacén y día, un trabajo
 * atascado bloquearía ese día para siempre. Pasado el plazo se marca FAILED y se puede volver a pedir.
 *
 * <p>Si el resultado llegase más tarde, se ignora (FAILED es un estado final): el plazo debe ser holgado
 * respecto al tiempo de cálculo del optimizador.
 */
@Component
@EnableScheduling
class JobReaper {

    private static final Logger log = LoggerFactory.getLogger(JobReaper.class);

    private final OptimizationJobRepository jobs;
    private final Duration timeout;

    JobReaper(OptimizationJobRepository jobs, @Value("${rutaflex.jobs.timeout}") Duration timeout) {
        this.jobs = jobs;
        this.timeout = timeout;
    }

    @Scheduled(fixedDelayString = "${rutaflex.jobs.reaper-interval}")
    void reap() {
        try {
            int closed = jobs.failStale(timeout.toSeconds(),
                    "The job did not finish within " + describe(timeout) + ": its result never arrived");
            if (closed > 0) {
                log.warn("Closed {} stuck optimization job(s) after {}", closed, describe(timeout));
            }
        } catch (RuntimeException e) {
            log.error("Could not look for stuck optimization jobs; will try again", e);
        }
    }

    private static String describe(Duration duration) {
        return duration.toSeconds() % 60 == 0 && duration.toMinutes() > 0
                ? duration.toMinutes() + " minutes" : duration.toSeconds() + " seconds";
    }
}
