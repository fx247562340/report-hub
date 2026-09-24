package com.reporthub.common;

import java.time.Instant;

public record ApiResponse<T>(boolean ok, T data, String error, Instant ts) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, Instant.now());
    }

    public static ApiResponse<Void> fail(String error) {
        return new ApiResponse<>(false, null, error, Instant.now());
    }
}
