package com.dunghaiquyen.sales;

import com.dunghaiquyen.sales.config.SalesProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(SalesProperties.class)
@EnableScheduling
public class SalesServiceApplication {
    public static void main(String[] args) { SpringApplication.run(SalesServiceApplication.class, args); }
}
