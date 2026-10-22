package cn.luwneshacha.platform.storage;

import static org.junit.jupiter.api.Assertions.*;
import com.qcloud.cos.endpoint.SuffixEndpointBuilder;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.COSObjectInputStream;
import jakarta.validation.Validation;
import java.io.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class CosObjectStorageTest {
  @Test void internalEndpointSuffixKeepsBucketPrefix() {
    var builder=new SuffixEndpointBuilder("cos-internal.ap-shanghai.myqcloud.com");
    assertEquals("thesis-1250000000.cos-internal.ap-shanghai.myqcloud.com",
        builder.buildGeneralApiEndpoint("thesis-1250000000"));
  }

  @Test void blankEndpointFallsBackToPublicDefault() {
    var blank=new CosProperties("thesis-1250000000","ap-shanghai","id","key","  ",null);
    assertNull(blank.endpoint());
    var internal=new CosProperties("thesis-1250000000","ap-shanghai","id","key",
        " cos-internal.ap-shanghai.myqcloud.com ",null);
    assertEquals("cos-internal.ap-shanghai.myqcloud.com",internal.endpoint());
  }

  @Test void endpointMustBeHostSuffix() {
    try(var factory=Validation.buildDefaultValidatorFactory()) {
      var validator=factory.getValidator();
      assertTrue(validator.validate(new CosProperties("thesis-1250000000","ap-shanghai","id","key","  ",null)).isEmpty());
      assertFalse(validator.validate(new CosProperties("thesis-1250000000","ap-shanghai","id","key",
          "https://cos-internal.ap-shanghai.myqcloud.com",null)).isEmpty());
    }
  }

  @Test void storageFailureDoesNotExposeSdkMessageOrCause() {
    var raw=new CosServiceException("secret signed URL",new IllegalArgumentException("credential"));
    raw.setRequestId("request-123"); raw.setStatusCode(503);
    var safe=CosObjectStorage.failure("upload",raw);
    assertNull(safe.getCause());
    assertFalse(safe.getMessage().contains("secret"));
    assertFalse(safe.getMessage().contains("credential"));
    assertTrue(safe.getMessage().contains("request-123"));
    assertTrue(safe.getMessage().contains("503"));
  }

  @Test void readDeadlineAbortsStalledStreamAndRejectsLateReads() throws Exception {
    var aborted=new CountDownLatch(1);
    var sdkStream=new COSObjectInputStream(new ByteArrayInputStream(new byte[]{1}),null) {
      @Override public void abort() {aborted.countDown();}
    };
    try(var scheduler=Executors.newSingleThreadScheduledExecutor();
        var bounded=CosObjectStorage.boundedDownload(sdkStream,scheduler,System.nanoTime()+Duration.ofMillis(50).toNanos())) {
      assertTrue(aborted.await(2,TimeUnit.SECONDS));
      assertThrows(IOException.class,bounded::read);
    }
  }
}
