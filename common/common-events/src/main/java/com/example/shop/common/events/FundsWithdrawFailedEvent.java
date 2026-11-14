package com.example.shop.common.events;

import java.math.BigDecimal;

public record FundsWithdrawFailedEvent(Long orderId, Long userId, Long accountId,
                                       BigDecimal amount, String reason) {}