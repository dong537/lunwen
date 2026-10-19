package cn.luwneshacha.platform.storage;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated @ConfigurationProperties("platform.cos")
public record CosProperties(@NotBlank String bucket, @NotBlank String region,
    @NotBlank String secretId, @NotBlank String secretKey) {
  @Override public String toString() { return "CosProperties[bucket=" + bucket + ", region=" + region + ", credentials=REDACTED]"; }
}
