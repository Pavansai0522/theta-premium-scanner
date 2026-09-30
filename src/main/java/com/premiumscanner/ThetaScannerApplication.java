package com.premiumscanner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ThetaScannerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ThetaScannerApplication.class, args);
    }
}
