package com.example.shop.common.events;

public final class Topics {
    public static final String USER_CREATED          = "user.created";
    public static final String ACCOUNT_CREATED       = "account.created";
    public static final String FUNDS_DEPOSITED       = "funds.deposited";
    public static final String ORDER_CREATED         = "order.created";
    public static final String FUNDS_WITHDRAWN       = "funds.withdrawn";
    public static final String FUNDS_WITHDRAW_FAILED = "funds.withdraw.failed";
    public static final String ORDER_PAID            = "order.paid";
    public static final String ORDER_FAILED          = "order.failed";
    public static final String NOTIFICATION_SENT     = "notification.sent";

    private Topics() {}
}