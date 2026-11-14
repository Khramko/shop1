package com.example.shop.common.events;

import java.math.BigDecimal;

/**
 * Все события и команды, используемые в Saga (v2).
 * Один файл — чтобы не плодить 20 record'ов по отдельным файлам.
 * Каждый record — это отдельный вложенный public static record.
 */
public final class SagaEvents {

    private SagaEvents() {}

    // --- order-service → участники ---
    public record OrderSagaStartedEvent(
            Long orderId, Long userId, String sku, int qty,
            String deliverySlot, BigDecimal price) {}

    public record ReserveFundsCommand(Long orderId, Long userId, BigDecimal amount) {}
    public record ReleaseFundsCommand(Long orderId, Long userId, BigDecimal amount) {}

    public record ReserveStockCommand(Long orderId, String sku, int qty) {}
    public record ReleaseStockCommand(Long orderId, String sku, int qty) {}

    public record ReserveCourierCommand(Long orderId, String slot) {}
    public record ReleaseCourierCommand(Long orderId, String slot) {}

    // --- billing-service → order-service ---
    public record FundsReservedEvent(Long orderId, Long accountId, BigDecimal balance) {}
    public record FundsReserveFailedEvent(Long orderId, String reason) {}
    public record FundsReleasedEvent(Long orderId) {}
    public record FundsReleaseFailedEvent(Long orderId, String reason) {}

    // --- warehouse-service → order-service ---
    public record StockReservedEvent(Long orderId, String sku, int qty) {}
    public record StockReserveFailedEvent(Long orderId, String reason) {}
    public record StockReleasedEvent(Long orderId) {}
    public record StockReleaseFailedEvent(Long orderId, String reason) {}

    // --- delivery-service → order-service ---
    public record CourierReservedEvent(Long orderId, Long courierId) {}
    public record CourierReserveFailedEvent(Long orderId, String reason) {}
    public record CourierReleasedEvent(Long orderId) {}
    public record CourierReleaseFailedEvent(Long orderId, String reason) {}
}
