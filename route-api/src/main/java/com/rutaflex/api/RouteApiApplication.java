package com.rutaflex.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RouteApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(RouteApiApplication.class, args);
    }
}
