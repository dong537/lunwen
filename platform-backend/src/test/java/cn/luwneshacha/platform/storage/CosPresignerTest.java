package cn.luwneshacha.platform.storage;

import static org.junit.jupiter.api.Assertions.*;
import cn.luwneshacha.platform.submission.SubmissionService;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CosPresignerTest {
  private static CosProperties properties(String publicEndpoint) {
    return new CosProperties("thesis-1250000000","ap-beijing","AKIDtest","secret",null,publicEndpoint);
  }

  @Test void uploadUrlUsesPublicEndpointAndSignsLengthAndType() throws Exception {
    try(var presigner=new CosPresigner(properties("cos.ap-beijing.myqcloud.com"))) {
      var ticket=presigner.uploadUrl("submissions/test/original.docx",1234,
          SubmissionService.DOCX,Instant.now().plusSeconds(600));
      assertEquals("thesis-1250000000.cos.ap-beijing.myqcloud.com",ticket.url().getHost());
      assertFalse(ticket.url().getHost().contains("cos-internal"));
      var decoded=URLDecoder.decode(ticket.url().getQuery(),StandardCharsets.UTF_8);
      assertTrue(decoded.contains("q-header-list=content-length;content-type;host"));
      assertTrue(decoded.contains("q-signature="));
    }
  }

  @Test void downloadUrlSetsSignedResponseHeaders() throws Exception {
    try(var presigner=new CosPresigner(properties(null))) {
      var ticket=presigner.downloadUrl("submissions/test/original.docx","论文.docx",
          Instant.now().plusSeconds(600));
      assertEquals("thesis-1250000000.cos.ap-beijing.myqcloud.com",ticket.url().getHost());
      var decoded=URLDecoder.decode(ticket.url().getQuery(),StandardCharsets.UTF_8);
      assertTrue(decoded.contains("response-content-disposition=attachment"));
      assertTrue(decoded.contains("response-cache-control=private, no-store"));
    }
  }
}
