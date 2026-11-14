package com.example.shop.delivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.example.shop")
public class DeliveryServiceApplication {
    public static void main(String[] args) { SpringApplication.run(DeliveryServiceApplication.class, args); }
}
