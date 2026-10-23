package cn.luwneshacha.thesis.api;

import cn.luwneshacha.thesis.inspection.InspectionException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public final class ApiExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(InspectionException.class)
  public ResponseEntity<ApiError> inspection(InspectionException error, HttpServletRequest request) {
    String requestId = requestId(request);
    if (error.getCause() != null) LOG.error("Inspection failed requestId={} code={}", requestId, error.code(), error);
    HttpStatus status = switch (error.code()) {
      case "RESOURCE_LIMIT" -> HttpStatus.PAYLOAD_TOO_LARGE;
      case "ANNOTATION_NOT_FOUND", "REPORT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
      case "REQUEST_ID_CONFLICT" -> HttpStatus.CONFLICT;
      case "PARSER_FAILED" -> HttpStatus.UNPROCESSABLE_ENTITY;
      case "STORAGE_FAILED" -> HttpStatus.INTERNAL_SERVER_ERROR;
      default -> HttpStatus.BAD_REQUEST;
    };
    return ResponseEntity.status(status).body(new ApiError(requestId, error.code(), error.getMessage()));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ApiError> tooLarge(HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(new ApiError(requestId(request), "RESOURCE_LIMIT", "file exceeds configured upload limit"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> unexpected(Exception error, HttpServletRequest request) {
    String requestId = requestId(request);
    LOG.error("Unhandled API error requestId={}", requestId, error);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(requestId, "INTERNAL_ERROR", "unexpected server error"));
  }

  private static String requestId(HttpServletRequest request) {
    String value = request.getHeader("X-Request-Id");
    if (value == null || value.isBlank()) return UUID.randomUUID().toString();
    try {
      UUID parsed = UUID.fromString(value);
      return parsed.toString().equalsIgnoreCase(value) ? parsed.toString() : UUID.randomUUID().toString();
    } catch (IllegalArgumentException error) {
      return UUID.randomUUID().toString();
    }
  }
}
