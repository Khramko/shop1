package com.example.shop.common.events;

public final class Topics {
    // v1 (async, без саги) — оставлены для обратной совместимости
    public static final String USER_CREATED          = "user.created";
    public static final String ORDER_CREATED         = "order.created";
    public static final String FUNDS_WITHDRAWN       = "funds.withdrawn";
    public static final String FUNDS_WITHDRAW_FAILED = "funds.withdraw.failed";
    public static final String ORDER_PAID            = "order.paid";
    public static final String ORDER_FAILED          = "order.failed";

    // v2 (Saga / orchestration)
    public static final String ORDER_SAGA_STARTED       = "order.saga.started";

    public static final String FUNDS_RESERVE_COMMAND   = "funds.reserve.command";
    public static final String FUNDS_RELEASE_COMMAND   = "funds.release.command";
    public static final String FUNDS_RESERVED          = "funds.reserved";
    public static final String FUNDS_RESERVE_FAILED    = "funds.reserve.failed";
    public static final String FUNDS_RELEASED          = "funds.released";
    public static final String FUNDS_RELEASE_FAILED    = "funds.release.failed";

    public static final String STOCK_RESERVE_COMMAND   = "stock.reserve.command";
    public static final String STOCK_RELEASE_COMMAND   = "stock.release.command";
    public static final String STOCK_RESERVED          = "stock.reserved";
    public static final String STOCK_RESERVE_FAILED    = "stock.reserve.failed";
    public static final String STOCK_RELEASED          = "stock.released";
    public static final String STOCK_RELEASE_FAILED    = "stock.release.failed";

    public static final String COURIER_RESERVE_COMMAND = "courier.reserve.command";
    public static final String COURIER_RELEASE_COMMAND = "courier.release.command";
    public static final String COURIER_RESERVED        = "courier.reserved";
    public static final String COURIER_RESERVE_FAILED  = "courier.reserve.failed";
    public static final String COURIER_RELEASED        = "courier.released";
    public static final String COURIER_RELEASE_FAILED  = "courier.release.failed";

    private Topics() {}
}
