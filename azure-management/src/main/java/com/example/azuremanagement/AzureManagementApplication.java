package com.example.azuremanagement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling  // Enable Spring scheduling
public class AzureManagementApplication {
    public static void main(String[] args) {
        SpringApplication.run(AzureManagementApplication.class, args);
    }
}
