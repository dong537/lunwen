package cn.luwneshacha.platformtests;

import static org.junit.jupiter.api.Assertions.*;
import cn.luwneshacha.platform.storage.CosProperties;
import cn.luwneshacha.platform.submission.TransferProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class StoragePropertiesBindingTest {
  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(Config.class)
      .withPropertyValues(
          "platform.cos.bucket=thesis-1250000000",
          "platform.cos.region=ap-beijing",
          "platform.cos.secret-id=id",
          "platform.cos.secret-key=secret",
          "platform.cos.endpoint=",
          "platform.cos.public-endpoint=");

  @Test void defaultsToProxyAndPublicCosEndpoint() {
    runner.run(context -> {
      var cos=context.getBean(CosProperties.class);
      assertNull(cos.endpoint());
      assertNull(cos.publicEndpoint());
      var transfer=context.getBean(TransferProperties.class);
      assertEquals(TransferProperties.Mode.PROXY,transfer.mode());
      assertEquals(600,transfer.presignExpireSeconds());
    });
  }

  @Test void bindsDirectModeAndPublicEndpoint() {
    runner.withPropertyValues(
        "platform.transfer.mode=direct",
        "platform.transfer.presign-expire-seconds=120",
        "platform.cos.public-endpoint=cos.ap-beijing.myqcloud.com").run(context -> {
      var cos=context.getBean(CosProperties.class);
      assertEquals("cos.ap-beijing.myqcloud.com",cos.publicEndpoint());
      var transfer=context.getBean(TransferProperties.class);
      assertEquals(TransferProperties.Mode.DIRECT,transfer.mode());
      assertEquals(120,transfer.presignExpireSeconds());
    });
  }

  @Test void rejectsInternalBrowserEndpoint() {
    runner.withPropertyValues("platform.cos.public-endpoint=cos-internal.ap-beijing.myqcloud.com")
        .run(context -> assertTrue(context.getStartupFailure()!=null));
  }

  @Configuration
  @EnableConfigurationProperties({CosProperties.class, TransferProperties.class})
  static class Config {}
}
