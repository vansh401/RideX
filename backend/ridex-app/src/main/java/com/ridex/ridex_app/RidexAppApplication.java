package com.ridex.ridex_app;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RidexAppApplication {

    // Runs once, when the JVM first loads this class. This happens both when
    // the app starts via main() and when tests start Spring without main().
    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(RidexAppApplication.class, args);
    }
}