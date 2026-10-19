package cn.luwneshacha.platform.common;

import java.util.LinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import jakarta.servlet.http.HttpServletResponse;

@RestControllerAdvice
public final class ApiExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiResponse<Void>> api(ApiException e, HttpServletResponse response) {
    clearDownloadHeaders(response);
    return ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_JSON).body(ApiResponse.fail(e.code(), e.getMessage()));
  }
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException e) {
    var fields = new LinkedHashMap<String, String>();
    e.getBindingResult().getFieldErrors().forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
    return ResponseEntity.badRequest().body(ApiResponse.fail("VALIDATION_ERROR", "请检查填写内容", fields));
  }
  @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
      MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
  ResponseEntity<ApiResponse<Void>> malformed(Exception e) {
    return ResponseEntity.badRequest().body(ApiResponse.fail("INVALID_REQUEST", "请求内容不正确，请检查字段和格式"));
  }
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<ApiResponse<Void>> tooLarge() {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ApiResponse.fail("FILE_TOO_LARGE", "上传文件超过允许大小"));
  }
  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ApiResponse<Void>> missingRoute() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.fail("NOT_FOUND", "请求的内容不存在"));
  }
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ApiResponse<Void>> wrongMethod() {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiResponse.fail("METHOD_NOT_ALLOWED", "请求方式不支持"));
  }
  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ApiResponse<Void>> conflict(DataIntegrityViolationException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail("DATA_CONFLICT", "数据已被其他操作修改，或账号已存在，请刷新后重试"));
  }
  @ExceptionHandler(TaskRejectedException.class)
  ResponseEntity<ApiResponse<Void>> downloadBusy(HttpServletResponse response) {
    clearDownloadHeaders(response);
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
        .body(ApiResponse.fail("DOWNLOAD_BUSY", "当前下载人数较多，请稍后重试"));
  }
  @ExceptionHandler(AsyncRequestTimeoutException.class)
  ResponseEntity<ApiResponse<Void>> downloadTimeout(HttpServletResponse response) {
    clearDownloadHeaders(response);
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
        .body(ApiResponse.fail("DOWNLOAD_TIMEOUT", "文件下载超时，请检查网络后重试"));
  }
  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ApiResponse<Void>> forbidden() {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.fail("FORBIDDEN", "没有权限执行此操作"));
  }
  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiResponse<Void>> unexpected(Exception e, HttpServletResponse response) {
    // Exception messages can contain submitted credentials or document text; log only the type and trace ID.
    LOG.error("Request failed type={}", e.getClass().getName());
    clearDownloadHeaders(response);
    return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_JSON).body(ApiResponse.fail("INTERNAL_ERROR", "服务暂时不可用，请稍后重试"));
  }
  private static void clearDownloadHeaders(HttpServletResponse response) {
    if (!response.isCommitted()) {
      response.setHeader(HttpHeaders.CONTENT_DISPOSITION, null);
      response.setHeader(HttpHeaders.CONTENT_LENGTH, null);
    }
  }
}
