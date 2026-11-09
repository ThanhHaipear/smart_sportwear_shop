package com.dunghaiquyen.ecommerce.common.response;

import java.util.List;
import org.slf4j.MDC;

/**
 * Single response envelope for every controller, per THIET_KE_SAN_PHAM.md 10.3/10.4.
 */
public record ApiResponse<T>(
        boolean success,
        String message,
        T data,
        Object meta,
        List<ApiFieldError> errors,
        String traceId) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "OK", data, null, null, null);
    }

    public static <T> ApiResponse<T> ok(T data, Object meta) {
        return new ApiResponse<>(true, "OK", data, meta, null, null);
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data, null, null, null);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, message, null, null, null, currentTraceId());
    }

    public static <T> ApiResponse<T> error(String message, List<ApiFieldError> errors) {
        return new ApiResponse<>(false, message, null, null, errors, currentTraceId());
    }

    private static String currentTraceId() {
        return MDC.get("traceId");
    }
}
