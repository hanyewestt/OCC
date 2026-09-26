package com.csd.occ.bunching;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BunchingApplication {
    public static void main(String[] args) {
        SpringApplication.run(BunchingApplication.class, args);
    }
}
