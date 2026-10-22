package cn.luwneshacha.platform.storage;

import com.qcloud.cos.*;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.endpoint.SuffixEndpointBuilder;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.*;
import com.qcloud.cos.region.Region;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CosObjectStorage implements ObjectStorage {
  public static final Duration REQUEST_BUDGET=Duration.ofMinutes(5);
  private final COSClient client;
  private final COSClient metadataClient;
  private final String bucket;
  private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(
      Thread.ofPlatform().daemon(true).name("cos-read-deadline").factory());
  public CosObjectStorage(CosProperties properties) {
    client = createClient(properties,10_000,60_000,Math.toIntExact(REQUEST_BUDGET.toMillis()));
    metadataClient = createClient(properties,5_000,15_000,15_000);
    bucket = properties.bucket();
  }
  private static COSClient createClient(CosProperties properties,int connectionTimeout,int socketTimeout,int requestTimeout) {
    var config = new ClientConfig(new Region(properties.region()));
    config.setHttpProtocol(HttpProtocol.https);
    if (properties.endpoint() != null)
      config.setEndpointBuilder(new SuffixEndpointBuilder(properties.endpoint()));
    config.setConnectionTimeout(connectionTimeout);
    config.setSocketTimeout(socketTimeout);
    config.setRequestTimeOutEnable(true);
    config.setRequestTimeout(requestTimeout);
    config.setMaxErrorRetry(0);
    return new COSClient(new BasicCOSCredentials(properties.secretId(), properties.secretKey()), config);
  }
  @Override public void put(String key, InputStream content, long size, String type) throws IOException {
    var metadata = new ObjectMetadata(); metadata.setContentLength(size); metadata.setContentType(type);
    try {
      var request = new PutObjectRequest(bucket, key, content, metadata);
      request.setCannedAcl(CannedAccessControlList.Private);
      client.putObject(request);
    } catch (RuntimeException error) { throw failure("upload",error); }
  }
  @Override public StoredObject get(String key) throws IOException {
    long deadline=System.nanoTime()+REQUEST_BUDGET.toNanos();
    try {
      var object = client.getObject(bucket, key);
      return new StoredObject(boundedDownload(object.getObjectContent(),deadlines,deadline),object.getObjectMetadata().getContentLength());
    } catch (RuntimeException error) { throw failure("download",error); }
  }
  @Override public Optional<StoredMetadata> head(String key) throws IOException {
    try {
      var metadata=metadataClient.getObjectMetadata(bucket,key);
      return Optional.of(new StoredMetadata(metadata.getContentLength(),metadata.getContentType(),metadata.getETag()));
    } catch (CosServiceException error) {
      if (error.getStatusCode()==404) return Optional.empty();
      throw failure("head",error);
    } catch (RuntimeException error) { throw failure("head",error); }
  }
  static InputStream boundedDownload(COSObjectInputStream source,ScheduledExecutorService scheduler,long deadline) {
    var abort=scheduler.schedule(()->{
      try {source.abort();}
      catch(RuntimeException error) {LoggerFactory.getLogger(CosObjectStorage.class).warn("cos_read_abort_failed type={}",error.getClass().getSimpleName());}
    },Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
    return new FilterInputStream(source) {
      private void checkDeadline() throws IOException {
        if(System.nanoTime()>=deadline) throw new IOException("COS download total deadline exceeded");
      }
      @Override public int read() throws IOException {
        checkDeadline();
        try {int value=source.read();checkDeadline();return value;}
        catch(IOException|RuntimeException error){throw failure("read",error);}
      }
      @Override public int read(byte[] bytes,int offset,int length) throws IOException {
        checkDeadline();
        try {int value=source.read(bytes,offset,length);checkDeadline();return value;}
        catch(IOException|RuntimeException error){throw failure("read",error);}
      }
      @Override public void close() throws IOException {
        abort.cancel(false);
        try {source.abort();source.close();} catch(IOException|RuntimeException error){throw failure("close",error);}
      }
    };
  }
  @Override public void deletePrefix(String prefix) throws IOException {
    if (!prefix.matches("submissions/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/(?:attempt-[1-3]/)?"))
      throw new IllegalArgumentException("invalid submission prefix");
    try {
      String marker = null;
      do {
        var request = new ListObjectsRequest();
        request.setBucketName(bucket); request.setPrefix(prefix); request.setMaxKeys(1000); request.setMarker(marker);
        var listing = client.listObjects(request);
        for (var object : listing.getObjectSummaries()) client.deleteObject(bucket, object.getKey());
        marker = listing.isTruncated() ? listing.getNextMarker() : null;
      } while (marker != null);
    } catch (RuntimeException error) { throw failure("cleanup",error); }
  }
  static IOException failure(String operation,Exception error) {
    String detail="type="+error.getClass().getSimpleName();
    if(error instanceof CosServiceException service) {
      String requestId=service.getRequestId();
      if(requestId==null||!requestId.matches("[A-Za-z0-9-]{1,128}")) requestId="unavailable";
      detail+=" status="+service.getStatusCode()+" requestId="+requestId;
    }
    // SDK messages and nested causes can contain signed URLs or credentials.
    return new IOException("COS "+operation+" failed "+detail);
  }
  @PreDestroy public void close() { deadlines.shutdownNow();client.shutdown();metadataClient.shutdown(); }
}
