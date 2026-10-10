package com.example.venue.common;

import java.time.OffsetDateTime;

public class Result<T> {
    private int code;
    private String message;
    private T data;
    private OffsetDateTime timestamp;

    public Result() {
    }

    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = OffsetDateTime.now();
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    public static Result<Void> ok() {
        return ok(null);
    }

    public static Result<Void> fail(ResultCode code, String message) {
        return new Result<>(code.getCode(), message == null ? code.getMessage() : message, null);
    }

    public static <T> Result<T> fail(ResultCode code, String message, T data) {
        return new Result<>(code.getCode(), message == null ? code.getMessage() : message, data);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(OffsetDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
