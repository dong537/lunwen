package cn.luwneshacha.platform.submission;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("platform.transfer")
public record TransferProperties(@DefaultValue("proxy") Mode mode,
    @DefaultValue("600") int presignExpireSeconds) {
  public enum Mode { PROXY, DIRECT }

  public TransferProperties {
    if (mode == null) mode = Mode.PROXY;
    if (presignExpireSeconds < 60 || presignExpireSeconds > 3600)
      throw new IllegalArgumentException("platform.transfer.presign-expire-seconds 必须为60至3600秒");
  }
}
