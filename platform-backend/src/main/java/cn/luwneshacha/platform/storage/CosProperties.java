package cn.luwneshacha.platform.storage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated @ConfigurationProperties("platform.cos")
public record CosProperties(@NotBlank String bucket, @NotBlank String region,
    @NotBlank String secretId, @NotBlank String secretKey,
    @Pattern(regexp = "^[A-Za-z0-9.-]+$",
        message = "platform.cos.endpoint 只能是域名后缀，例如 cos-internal.ap-shanghai.myqcloud.com")
    String endpoint,
    @Pattern(regexp = "^[A-Za-z0-9.-]+$",
        message = "platform.cos.public-endpoint 只能是域名后缀，例如 cos.ap-shanghai.myqcloud.com")
    String publicEndpoint) {
  public CosProperties {
    endpoint = normalize(endpoint);
    publicEndpoint = normalize(publicEndpoint);
    if (publicEndpoint != null && publicEndpoint.toLowerCase(Locale.ROOT).contains("cos-internal"))
      throw new IllegalArgumentException("platform.cos.public-endpoint 不能使用内网域名");
  }
  private static String normalize(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
  @Override public String toString() {
    return "CosProperties[bucket=" + bucket + ", region=" + region
        + ", endpoint=" + (endpoint == null ? "public" : endpoint)
        + ", publicEndpoint=" + (publicEndpoint == null ? "public" : publicEndpoint)
        + ", credentials=REDACTED]";
  }
}
