package com.example.shop.common.events;

import java.math.BigDecimal;

public record OrderFailedEvent(Long orderId, Long userId, BigDecimal price, String reason) {}
