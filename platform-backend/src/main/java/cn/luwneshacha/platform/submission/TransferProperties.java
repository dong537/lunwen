package cn.luwneshacha.platform.submission;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.transfer")
public record TransferProperties(Mode mode, int presignExpireSeconds) {
  public enum Mode { PROXY, DIRECT }

  public TransferProperties {
    if (mode == null)
      throw new IllegalArgumentException("platform.transfer.mode 必须配置为 proxy 或 direct");
    if (presignExpireSeconds < 60 || presignExpireSeconds > 3600)
      throw new IllegalArgumentException("platform.transfer.presign-expire-seconds 必须为60至3600秒");
  }
}
