package com.dunghaiquyen.catalog.api;

import java.util.List;

public record ApiResponse<T>(boolean success, String message, T data, Object meta,
                             List<Object> errors, String traceId) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "OK", data, null, null, null);
    }

    public static <T> ApiResponse<T> ok(T data, Object meta) {
        return new ApiResponse<>(true, "OK", data, meta, null, null);
    }
}
