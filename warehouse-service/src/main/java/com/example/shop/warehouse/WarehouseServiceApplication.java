package com.example.shop.warehouse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.example.shop")
public class WarehouseServiceApplication {
    public static void main(String[] args) { SpringApplication.run(WarehouseServiceApplication.class, args); }
}
