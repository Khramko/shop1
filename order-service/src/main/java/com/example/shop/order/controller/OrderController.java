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
    public ResponseEntity<?> create(@AuthenticationPrincipal AuthenticatedUser principal,
                                    @Valid @RequestBody CreateOrderRequest req) {
        if (orderService.isSagaEnabled()) {
            if (req.getSku() == null || req.getSku().isBlank()
                    || req.getQty() == null || req.getQty() < 1
                    || req.getDeliverySlot() == null || req.getDeliverySlot().isBlank()) {
                return ResponseEntity.badRequest().body(java.util.Map.of(
                        "error", "validation",
                        "details", "sku, qty, deliverySlot are required when saga enabled"));
            }
        }
        OrderResponse r = orderService.create(principal.id(), req.getPrice(),
                req.getSku(), req.getQty(), req.getDeliverySlot());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(r);
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> get(@AuthenticationPrincipal AuthenticatedUser principal,
                                             @PathVariable Long id) {
        OrderResponse r = orderService.get(principal.id(), id);
        return r == null ? ResponseEntity.status(HttpStatus.FORBIDDEN).build() : ResponseEntity.ok(r);
    }

    @GetMapping
    public ResponseEntity<List<OrderResponse>> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(orderService.listByUser(principal.id()));
    }
}
