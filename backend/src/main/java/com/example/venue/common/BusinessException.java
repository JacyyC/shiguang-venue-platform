package com.example.venue.common;

public class BusinessException extends RuntimeException {
    private final ResultCode code;

    public BusinessException(ResultCode code, String message) {
        super(message);
        this.code = code;
    }

    public ResultCode getCode() {
        return code;
    }

    public static BusinessException badRequest(String message) {
        return new BusinessException(ResultCode.BAD_REQUEST, message);
    }

    public static BusinessException conflict(String message) {
        return new BusinessException(ResultCode.CONFLICT, message);
    }

    public static BusinessException forbidden(String message) {
        return new BusinessException(ResultCode.FORBIDDEN, message);
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(ResultCode.NOT_FOUND, message);
    }
}
