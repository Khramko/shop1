package com.example.shop.warehouse.controller;

import com.example.shop.warehouse.model.Stock;
import com.example.shop.warehouse.repository.StockRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/warehouse/stock")
public class StockController {

    private final StockRepository repo;
    public StockController(StockRepository repo) { this.repo = repo; }

    @GetMapping
    public List<Stock> list() { return repo.findAll(); }
}
