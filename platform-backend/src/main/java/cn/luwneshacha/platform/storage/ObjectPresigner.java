package cn.luwneshacha.platform.storage;

import java.net.URL;
import java.time.Instant;

/** Generates short-lived browser URLs. Implementations must never log the URL or its signature. */
public interface ObjectPresigner {
  record UploadTicket(URL url, Instant expiresAt) {}
  record DownloadTicket(URL url, String filename, Instant expiresAt) {}

  UploadTicket uploadUrl(String key, long size, String contentType, Instant expiresAt);
  DownloadTicket downloadUrl(String key, String filename, Instant expiresAt);
}
