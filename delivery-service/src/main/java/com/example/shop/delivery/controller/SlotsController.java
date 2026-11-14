package com.example.shop.delivery.controller;

import com.example.shop.delivery.model.CourierSlot;
import com.example.shop.delivery.repository.CourierSlotRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/delivery/slots")
public class SlotsController {

    private final CourierSlotRepository repo;
    public SlotsController(CourierSlotRepository repo) { this.repo = repo; }

    @GetMapping
    public List<CourierSlot> list() { return repo.findAll(); }
}
