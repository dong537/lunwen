package cn.luwneshacha.thesis.api;

import cn.luwneshacha.thesis.inspection.InspectionException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public final class ApiExceptionHandler {
  @ExceptionHandler(InspectionException.class)
  public ResponseEntity<ApiError> inspection(InspectionException error, HttpServletRequest request) {
    HttpStatus status = switch (error.code()) {
      case "RESOURCE_LIMIT" -> HttpStatus.PAYLOAD_TOO_LARGE;
      case "ANNOTATION_NOT_FOUND" -> HttpStatus.NOT_FOUND;
      case "PARSER_FAILED" -> HttpStatus.UNPROCESSABLE_ENTITY;
      default -> HttpStatus.BAD_REQUEST;
    };
    return ResponseEntity.status(status).body(new ApiError(requestId(request), error.code(), error.getMessage()));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ApiError> tooLarge(HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(new ApiError(requestId(request), "RESOURCE_LIMIT", "file exceeds configured upload limit"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> unexpected(HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(requestId(request), "INTERNAL_ERROR", "unexpected server error"));
  }

  private static String requestId(HttpServletRequest request) {
    String value = request.getHeader("X-Request-Id");
    return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
  }
}
