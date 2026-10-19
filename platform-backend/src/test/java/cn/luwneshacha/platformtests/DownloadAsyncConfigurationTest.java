package cn.luwneshacha.platformtests;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import cn.luwneshacha.platform.common.ApiExceptionHandler;
import cn.luwneshacha.platform.common.RequestIdFilter;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;

/** Verifies MVC executor selection and response behavior; role checks are covered by the real PG security suite. */
@SpringBootTest(classes = DownloadAsyncConfigurationTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class DownloadAsyncConfigurationTest {
  @SpringBootConfiguration
  @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
      SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
  @Import({Probe.class, ApiExceptionHandler.class, RequestIdFilter.class})
  static class App {}
  @RestController static class Probe {
    @GetMapping("/download-probe") ResponseEntity<StreamingResponseBody> stream() {
      return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
          .body(output -> output.write("download completed".getBytes(StandardCharsets.UTF_8)));
    }
  }
  @Autowired MockMvc mvc;
  @Autowired @Qualifier("applicationTaskExecutor") ThreadPoolTaskExecutor executor;
  @org.springframework.boot.test.web.server.LocalServerPort int port;

  @Test void streamingUsesExplicitTimeoutAndReturnsBytesAfterAsyncDispatch() throws Exception {
    var request = mvc.perform(get("/download-probe")).andExpect(request().asyncStarted()).andReturn();
    assertEquals(330_000L, request.getRequest().getAsyncContext().getTimeout());
    request.getAsyncResult(2000);
    mvc.perform(asyncDispatch(request)).andExpect(status().isOk()).andExpect(content().string("download completed"));
  }

  @Test void saturatedDownloadExecutorRejectsInsteadOfGrowingThreadsOrQueue() throws Exception {
    assertEquals(8, executor.getMaxPoolSize()); assertEquals(16, executor.getQueueCapacity());
    var release = new CountDownLatch(1);
    try {
      for (int i = 0; i < 24; i++) executor.execute(() -> {
        try { release.await(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      });
      try (var client = java.net.http.HttpClient.newHttpClient()) {
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/download-probe"))
            .timeout(java.time.Duration.ofSeconds(10)).GET().build();
        var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(503, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertTrue(response.body().contains("DOWNLOAD_BUSY"));
        var payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
        assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(), payload.path("requestId").asText());
      }
    } finally { release.countDown(); }
  }
}
