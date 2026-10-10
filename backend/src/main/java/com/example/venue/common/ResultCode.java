package com.example.venue.common;

public enum ResultCode {
    SUCCESS(0, "success"),
    BAD_REQUEST(40000, "参数错误"),
    UNAUTHORIZED(40100, "未登录或登录已过期"),
    FORBIDDEN(40300, "无权限访问"),
    NOT_FOUND(40400, "资源不存在"),
    CONFLICT(40900, "业务冲突"),
    VALIDATION_ERROR(42200, "参数校验失败"),
    TOO_MANY_REQUESTS(42900, "请求过于频繁"),
    PAYMENT_ERROR(46000, "支付处理失败"),
    SYSTEM_ERROR(50000, "系统异常");

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
