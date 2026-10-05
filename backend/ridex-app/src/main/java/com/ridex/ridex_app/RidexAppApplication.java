package com.ridex.ridex_app;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RidexAppApplication {

    public static void main(String[] args) {
        // Run the whole application in UTC (see timezone note in docs)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(RidexAppApplication.class, args);
    }
}