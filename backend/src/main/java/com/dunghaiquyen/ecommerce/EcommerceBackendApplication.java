package com.dunghaiquyen.ecommerce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan(
        basePackages = "com.dunghaiquyen.ecommerce",
        excludeFilters = {
            @ComponentScan.Filter(
                    type = FilterType.REGEX,
                    pattern = "com\\.dunghaiquyen\\.ecommerce\\.modules\\.(banner|collection|combo|notification|page|promotion|recommendation|review|shipping|wishlist)(\\..*)?"),
            @ComponentScan.Filter(
                    type = FilterType.REGEX,
                    pattern = "com\\.dunghaiquyen\\.ecommerce\\.infra\\.seed(\\..*)?")
        })
@ConfigurationPropertiesScan
@EnableScheduling
public class EcommerceBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(EcommerceBackendApplication.class, args);
	}

}
