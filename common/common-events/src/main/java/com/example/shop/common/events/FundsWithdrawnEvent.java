package com.example.shop.common.events;

import java.math.BigDecimal;

public record FundsWithdrawnEvent(Long orderId, Long userId, Long accountId,
                                  BigDecimal amount, BigDecimal balance) {}