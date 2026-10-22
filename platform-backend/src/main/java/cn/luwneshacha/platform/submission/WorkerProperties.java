package cn.luwneshacha.platform.submission;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("platform.worker")
public record WorkerProperties(@DefaultValue("true") boolean enabled,
    @DefaultValue("PT40M") Duration lease) {
  public WorkerProperties {
    if (lease == null || lease.compareTo(Duration.ofSeconds(30)) < 0)
      throw new IllegalArgumentException("platform.worker.lease 必须至少30秒");
  }
}
