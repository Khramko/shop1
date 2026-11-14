package com.example.shop.common.events;

import java.math.BigDecimal;

public record OrderCreatedEvent(Long orderId, Long userId, BigDecimal price) {}