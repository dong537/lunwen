package cn.luwneshacha.thesis.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.inspection.InspectionException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiExceptionHandlerTest {
  @Test
  void storageFailureReturnsServerError() {
    var request = new MockHttpServletRequest();
    request.addHeader("X-Request-Id", "request-123");

    var response = new ApiExceptionHandler().inspection(
        new InspectionException("STORAGE_FAILED", "cannot create inspection storage"), request);

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    assertNotEquals("request-123", response.getBody().requestId());
    assertEquals(response.getBody().requestId(), UUID.fromString(response.getBody().requestId()).toString());
  }

  @Test
  void normalizesValidRequestIdAndDoesNotTrustInvalidValues() {
    UUID expected = UUID.randomUUID();
    var request = new MockHttpServletRequest();
    request.addHeader("X-Request-Id", expected.toString().toUpperCase(java.util.Locale.ROOT));
    var validResponse = new ApiExceptionHandler().inspection(
        new InspectionException("STORAGE_FAILED", "cannot create inspection storage"), request);
    assertEquals(expected.toString(), validResponse.getBody().requestId());

    request = new MockHttpServletRequest();
    request.addHeader("X-Request-Id", "invalid-request-id");
    var invalidResponse = new ApiExceptionHandler().inspection(
        new InspectionException("INVALID_REQUEST_ID", "X-Request-Id must be a UUID"), request);
    assertTrue(isCanonicalUuid(invalidResponse.getBody().requestId()));
    assertNotEquals("invalid-request-id", invalidResponse.getBody().requestId());
  }

  private static boolean isCanonicalUuid(String value) {
    try { return UUID.fromString(value).toString().equals(value); }
    catch (IllegalArgumentException error) { return false; }
  }
}
