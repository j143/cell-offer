package com.uber.celloffer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CellOfferApplication {

    public static void main(String[] args) {
        SpringApplication.run(CellOfferApplication.class, args);
    }
}
