package com.reporthub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ReportHubApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReportHubApplication.class, args);
    }
}
