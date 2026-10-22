package cn.luwneshacha.platform.storage;

import com.qcloud.cos.*;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.endpoint.SuffixEndpointBuilder;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.model.ResponseHeaderOverrides;
import com.qcloud.cos.region.Region;
import jakarta.annotation.PreDestroy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import org.springframework.http.ContentDisposition;
import org.springframework.stereotype.Component;

/** Generates browser-facing URLs against the public COS endpoint, never the internal one. */
@Component
public class CosPresigner implements ObjectPresigner, AutoCloseable {
  private final COSClient client;
  private final String bucket;
  public CosPresigner(CosProperties properties) {
    var config = new ClientConfig(new Region(properties.region()));
    config.setHttpProtocol(HttpProtocol.https);
    if (properties.publicEndpoint() != null)
      config.setEndpointBuilder(new SuffixEndpointBuilder(properties.publicEndpoint()));
    config.setConnectionTimeout(5_000);
    config.setSocketTimeout(15_000);
    config.setRequestTimeOutEnable(true);
    config.setRequestTimeout(15_000);
    config.setMaxErrorRetry(0);
    client = new COSClient(new BasicCOSCredentials(properties.secretId(), properties.secretKey()), config);
    bucket = properties.bucket();
  }
  @Override public UploadTicket uploadUrl(String key, long size, String contentType, Instant expiresAt) {
    var request = new GeneratePresignedUrlRequest(bucket, key, HttpMethodName.PUT);
    request.setExpiration(Date.from(expiresAt));
    request.setContentType(contentType);
    request.putCustomRequestHeader(Headers.CONTENT_LENGTH, Long.toString(size));
    return new UploadTicket(client.generatePresignedUrl(request), expiresAt);
  }
  @Override public DownloadTicket downloadUrl(String key, String filename, Instant expiresAt) {
    var request = new GeneratePresignedUrlRequest(bucket, key, HttpMethodName.GET);
    request.setExpiration(Date.from(expiresAt));
    var overrides = new ResponseHeaderOverrides();
    overrides.setContentDisposition(ContentDisposition.attachment()
        .filename(filename, StandardCharsets.UTF_8).build().toString());
    overrides.setCacheControl("private, no-store");
    request.setResponseHeaders(overrides);
    return new DownloadTicket(client.generatePresignedUrl(request), filename, expiresAt);
  }
  @Override @PreDestroy public void close() { client.shutdown(); }
}
