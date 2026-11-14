package com.example.shop.order.controller;

import com.example.shop.common.security.AuthenticatedUser;
import com.example.shop.order.dto.CreateOrderRequest;
import com.example.shop.order.dto.OrderResponse;
import com.example.shop.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) { this.orderService = orderService; }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@AuthenticationPrincipal AuthenticatedUser principal,
                                                @Valid @RequestBody CreateOrderRequest req) {
        OrderResponse r = orderService.create(principal.id(), req.getPrice());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(r);
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> get(@AuthenticationPrincipal AuthenticatedUser principal,
                                             @PathVariable Long id) {
        OrderResponse r = orderService.get(principal.id(), id);
        return r == null ? ResponseEntity.status(HttpStatus.FORBIDDEN).build()
                         : ResponseEntity.ok(r);
    }

    @GetMapping
    public ResponseEntity<List<OrderResponse>> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(orderService.listByUser(principal.id()));
    }
}
