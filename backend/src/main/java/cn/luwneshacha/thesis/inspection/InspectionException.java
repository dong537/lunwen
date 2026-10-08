package cn.luwneshacha.thesis.inspection;

public final class InspectionException extends RuntimeException {
  private final String code;

  public InspectionException(String code, String message) {
    super(message);
    this.code = code;
  }

  public InspectionException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public String code() { return code; }
}
