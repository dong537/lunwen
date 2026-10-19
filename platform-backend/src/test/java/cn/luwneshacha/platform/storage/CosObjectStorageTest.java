package cn.luwneshacha.platform.storage;

import static org.junit.jupiter.api.Assertions.*;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.COSObjectInputStream;
import java.io.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class CosObjectStorageTest {
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
