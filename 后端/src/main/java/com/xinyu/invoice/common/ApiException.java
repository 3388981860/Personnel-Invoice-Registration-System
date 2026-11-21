package com.xinyu.invoice.common;

/** 业务异常：带上 HTTP 状态码和（可选的）出错字段 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String field;

    public ApiException(String message) {
        this(message, 400, null);
    }

    public ApiException(String message, int status) {
        this(message, status, null);
    }

    public ApiException(String message, int status, String field) {
        super(message);
        this.status = status;
        this.field = field;
    }

    public static ApiException of(int status, String message) {
        return new ApiException(message, status);
    }

    public static ApiException forbid(String message) {
        return new ApiException(message, 403);
    }

    public static ApiException notFound(String message) {
        return new ApiException(message, 404);
    }

    public int getStatus() { return status; }
    public String getField() { return field; }
}
