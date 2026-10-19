package cn.luwneshacha.platform.common;

import java.util.Map;
import org.slf4j.MDC;

public record ApiResponse<T>(T data, Error error, String requestId) {
  public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>(data, null, MDC.get("requestId")); }
  public static ApiResponse<Void> fail(String code, String message) {
    return new ApiResponse<>(null, new Error(code, message, Map.of()), MDC.get("requestId"));
  }
  public static ApiResponse<Void> fail(String code, String message, Map<String, String> fields) {
    return new ApiResponse<>(null, new Error(code, message, Map.copyOf(fields)), MDC.get("requestId"));
  }
  public record Error(String code, String message, Map<String, String> fieldErrors) {}
}
