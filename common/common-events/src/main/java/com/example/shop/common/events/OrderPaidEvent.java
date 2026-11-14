package com.example.shop.common.events;

import java.math.BigDecimal;

public record OrderPaidEvent(Long orderId, Long userId, BigDecimal price) {}