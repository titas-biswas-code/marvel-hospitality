package com.marvel.hospitality.creditcard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CreditCardPaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CreditCardPaymentServiceApplication.class, args);
    }
}
