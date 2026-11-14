package com.example.shop.order.service;

import com.example.shop.order.dto.OrderResponse;
import com.example.shop.order.model.Order;
import com.example.shop.order.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderEventPublisher publisher;

    public OrderService(OrderRepository orderRepository, OrderEventPublisher publisher) {
        this.orderRepository = orderRepository;
        this.publisher = publisher;
    }

    @Transactional
    public OrderResponse create(Long userId, BigDecimal price) {
        Order o = orderRepository.save(new Order(userId, price));
        publisher.publishOrderCreated(o);
        return OrderResponse.from(o);
    }

    @Transactional(readOnly = true)
    public OrderResponse get(Long userId, Long orderId) {
        return orderRepository.findByIdAndUserId(orderId, userId)
                .map(OrderResponse::from)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> listByUser(Long userId) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(OrderResponse::from).toList();
    }
}
