package com.xtx.common.core.result;

public enum ResultCode {

    SUCCESS(20000, "success"),
    BAD_REQUEST(40000, "bad request"),
    ORDER_DUPLICATE_SUBMIT(40020, "order duplicate submit"),
    ORDER_TOKEN_INVALID(40021, "order token invalid"),
    UNAUTHORIZED(40100, "unauthorized"),
    FORBIDDEN(40300, "forbidden"),
    NOT_FOUND(40400, "not found"),
    CONFLICT(40900, "conflict"),
    TOO_MANY_REQUESTS(42900, "too many requests"),
    INTERNAL_ERROR(50000, "internal error"),
    SERVICE_UNAVAILABLE(50300, "service unavailable");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
