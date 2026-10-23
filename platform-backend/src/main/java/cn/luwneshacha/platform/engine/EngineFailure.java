package cn.luwneshacha.platform.engine;

public class EngineFailure extends Exception {
  private final boolean retryable;
  public EngineFailure(boolean retryable, String message) { super(message); this.retryable=retryable; }
  public EngineFailure(boolean retryable, String message, Throwable cause) { super(message,cause); this.retryable=retryable; }
  public boolean retryable() { return retryable; }
}
