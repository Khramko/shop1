package com.example.shop.warehouse.repository;

import com.example.shop.warehouse.model.Stock;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockRepository extends JpaRepository<Stock, String> {}
