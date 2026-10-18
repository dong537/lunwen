package cn.luwneshacha.thesis.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.UUID;
import java.util.zip.ZipFile;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.nio.file.attribute.FileTime;
import org.springframework.web.multipart.MultipartFile;
import cn.luwneshacha.thesis.inspection.InspectionException;
import java.io.IOException;
import java.io.File;
import java.io.InputStream;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.PartName;
import org.docx4j.openpackaging.parts.WordprocessingML.CommentsPart;
import org.docx4j.wml.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class InspectionApiTest {
  private static final Path STORAGE_ROOT = Path.of(System.getProperty("java.io.tmpdir"),
      "thesis-inspection-api-" + UUID.randomUUID());

  @Autowired MockMvc mvc;
  @Autowired InspectionService inspectionService;
  @TempDir Path temp;

  @DynamicPropertySource
  static void configureStorage(DynamicPropertyRegistry registry) {
    registry.add("thesis.storage-root", STORAGE_ROOT::toString);
  }

  @AfterAll
  static void removeTestStorage() throws IOException {
    if (!Files.exists(STORAGE_ROOT)) return;
    try (var paths = Files.walk(STORAGE_ROOT)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
    }
  }

  @Test
  void uploadsDocxAndDownloadsAnnotationCopy() throws Exception {
    byte[] source = validDocx();
    var response = mvc.perform(multipart("/api/inspections")
        .file(new MockMultipartFile("file", "thesis.docx", MediaType.APPLICATION_OCTET_STREAM_VALUE, source))
        .param("annotate", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestId").isString())
        .andExpect(jsonPath("$.sourceSha256").isString())
        .andExpect(jsonPath("$.report.ruleSetId").value("school-v1"))
        .andExpect(jsonPath("$.annotation.available").value(true))
        .andReturn().getResponse().getContentAsString();
    var responseJson = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);
    String requestId = responseJson.get("requestId").asText();
    assertTrue(Files.isRegularFile(STORAGE_ROOT.resolve(requestId).resolve("annotated.docx")),
        "the annotated copy should be stored under the configured test directory");
    var annotationItems = responseJson.path("annotation").path("items");
    boolean bodySizeWritten = false;
    for (var item : annotationItems) {
      if ("BODY-SIZE".equals(item.path("ruleId").asText())
          && "WRITTEN".equals(item.path("status").asText())
          && "word/document.xml".equals(item.path("source").path("partName").asText())
          && item.path("source").path("elementPath").isArray()) bodySizeWritten = true;
    }
    assertTrue(bodySizeWritten, "the response should expose the annotation status at its source address");

    var download = mvc.perform(get("/api/inspections/{id}/annotated", requestId))
        .andExpect(status().isOk())
        .andReturn();
    assertTrue(download.getResponse().getContentType().startsWith("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
    assertTrue(download.getResponse().getContentAsByteArray().length > 0);
    Path annotated = temp.resolve("annotated.docx");
    java.nio.file.Files.write(annotated, download.getResponse().getContentAsByteArray());
    var pkg = WordprocessingMLPackage.load(annotated.toFile());
    var comments = (CommentsPart) pkg.getParts().getParts().get(new PartName("/word/comments.xml"));
    int resultCount = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("report").get("results").size();
    assertTrue(comments.getContents().getComment().size() < resultCount);
    String commentXml;
    try (ZipFile zip = new ZipFile(annotated.toFile())) {
      commentXml = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(),
          StandardCharsets.UTF_8);
    }
    assertTrue(commentXml.contains("宋体"), "同段字体问题不能被去重丢掉");
    assertTrue(commentXml.contains("字号"), "同段字号问题不能被去重丢掉");
  }

  @Test
  void usesValidRequestIdHeaderForResponseAndAnnotationDownload() throws Exception {
    String requestId = UUID.randomUUID().toString();
    var response = mvc.perform(multipart("/api/inspections")
        .file(new MockMultipartFile("file", "thesis.docx", MediaType.APPLICATION_OCTET_STREAM_VALUE, validDocx()))
        .header("X-Request-Id", requestId)
        .param("annotate", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestId").value(requestId))
        .andExpect(jsonPath("$.annotation.available").value(true))
        .andReturn().getResponse().getContentAsString();

    assertEquals(requestId, new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("requestId").asText());
    mvc.perform(get("/api/inspections/{id}/annotated", requestId)).andExpect(status().isOk());
  }

  @Test
  void rejectsNonUuidRequestIdBeforeCreatingStorage() throws Exception {
    mvc.perform(multipart("/api/inspections")
        .file(new MockMultipartFile("file", "thesis.docx", MediaType.APPLICATION_OCTET_STREAM_VALUE, validDocx()))
        .header("X-Request-Id", "../outside"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST_ID"));
  }

  @Test
  void rejectsDuplicateRequestIdWithoutReplacingStoredAnnotation() throws Exception {
    String requestId = UUID.randomUUID().toString();
    mvc.perform(multipart("/api/inspections")
        .file(new MockMultipartFile("file", "first.docx", MediaType.APPLICATION_OCTET_STREAM_VALUE, validDocx()))
        .header("X-Request-Id", requestId)
        .param("annotate", "true"))
        .andExpect(status().isOk());
    Path annotation = STORAGE_ROOT.resolve(requestId).resolve("annotated.docx");
    byte[] originalAnnotation = Files.readAllBytes(annotation);

    mvc.perform(multipart("/api/inspections")
        .file(new MockMultipartFile("file", "second.docx", MediaType.APPLICATION_OCTET_STREAM_VALUE, validDocx()))
        .header("X-Request-Id", requestId)
        .param("annotate", "false"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

    assertArrayEquals(originalAnnotation, Files.readAllBytes(annotation));
    mvc.perform(get("/api/inspections/{id}/annotated", requestId)).andExpect(status().isOk());
  }

  @Test
  void rejectsNonDocxAndMissingAnnotation() throws Exception {
    mvc.perform(multipart("/api/inspections")
        .file(new MockMultipartFile("file", "thesis.txt", MediaType.TEXT_PLAIN_VALUE, "bad".getBytes())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_DOCX"));

    mvc.perform(get("/api/inspections/missing/annotated"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ANNOTATION_NOT_FOUND"));
  }

  @Test
  void allowsTheNextLocalVitePortForBrowserUploads() throws Exception {
    mvc.perform(options("/api/inspections")
        .header("Origin", "http://127.0.0.1:5175")
        .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
            .string("Access-Control-Allow-Origin", "http://127.0.0.1:5175"));
  }

  @Test
  void allowsTheDeployedHttpsOriginForBrowserUploads() throws Exception {
    mvc.perform(options("/api/inspections")
        .header("Origin", "https://124.223.86.157")
        .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
            .string("Access-Control-Allow-Origin", "https://124.223.86.157"));
  }

  @Test
  void leavesAnnotationItemsEmptyWhenAnnotationWasNotRequested() throws Exception {
    var response = mvc.perform(multipart("/api/inspections")
        .file(new MockMultipartFile("file", "thesis.docx", MediaType.APPLICATION_OCTET_STREAM_VALUE, validDocx()))
        .param("annotate", "false"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotation.available").value(false))
        .andExpect(jsonPath("$.annotation.items").isArray())
        .andExpect(jsonPath("$.annotation.items").isEmpty())
        .andReturn().getResponse().getContentAsString();
    String requestId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("requestId").asText();
    assertTrue(Files.isRegularFile(STORAGE_ROOT.resolve(requestId).resolve("report.docx")), "every inspection should retain a Word report");
    var reportDownload = mvc.perform(get("/api/inspections/{id}/report", requestId))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    Path reportPath = Files.createTempFile("inspection-report-not-requested-", ".docx");
    try {
      Files.write(reportPath, reportDownload);
      try (ZipFile zip = new ZipFile(reportPath.toFile())) {
        String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(document.contains("本次未请求写入 Word 批注"));
      }
    } finally {
      Files.deleteIfExists(reportPath);
    }
  }

  @Test
  void expiresAnnotatedCopiesAfterConfiguredRetention() throws Exception {
    var response = mvc.perform(multipart("/api/inspections")
        .file(new MockMultipartFile("file", "thesis.docx", MediaType.APPLICATION_OCTET_STREAM_VALUE, validDocx()))
        .param("annotate", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotation.available").value(true))
        .andReturn().getResponse().getContentAsString();
    String requestId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("requestId").asText();
    Path requestDirectory = STORAGE_ROOT.resolve(requestId);
    Files.setLastModifiedTime(requestDirectory, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
    inspectionService.cleanupExpiredArtifacts();

    mvc.perform(get("/api/inspections/{id}/annotated", requestId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ANNOTATION_NOT_FOUND"));
    assertFalse(Files.exists(requestDirectory), "expired annotation output and its request directory must be deleted");
  }

  @Test
  void cleanupDoesNotDeleteAnInspectionWhileItIsInProgress() throws Exception {
    String requestId = UUID.randomUUID().toString();
    CountDownLatch transferStarted = new CountDownLatch(1);
    CountDownLatch allowTransfer = new CountDownLatch(1);
    byte[] source = validDocx();
    MultipartFile blockingFile = new MultipartFile() {
      @Override public String getName() { return "file"; }
      @Override public String getOriginalFilename() { return "thesis.docx"; }
      @Override public String getContentType() { return MediaType.APPLICATION_OCTET_STREAM_VALUE; }
      @Override public boolean isEmpty() { return false; }
      @Override public long getSize() { return source.length; }
      @Override public byte[] getBytes() { return source.clone(); }
      @Override public InputStream getInputStream() { return new java.io.ByteArrayInputStream(source); }
      @Override public void transferTo(File destination) throws IOException { throw new IOException("unexpected transfer path"); }
      @Override public void transferTo(Path destination) throws IOException {
        transferStarted.countDown();
        try {
          if (!allowTransfer.await(10, TimeUnit.SECONDS)) throw new IOException("test transfer timed out");
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new IOException("test transfer interrupted", error);
        }
        Files.write(destination, source);
      }
    };
    Path requestDirectory = STORAGE_ROOT.resolve(requestId);
    CompletableFuture<Void> inspection = CompletableFuture.runAsync(
        () -> inspectionService.inspect(blockingFile, false, requestId));
    try {
      assertTrue(transferStarted.await(5, TimeUnit.SECONDS), "inspection should reach file transfer");
      Files.setLastModifiedTime(requestDirectory, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
      inspectionService.cleanupExpiredArtifacts();
      assertTrue(Files.isDirectory(requestDirectory), "cleanup must skip active inspection requests");
    } finally {
      allowTransfer.countDown();
    }
    inspection.get(30, TimeUnit.SECONDS);
    assertTrue(Files.isRegularFile(requestDirectory.resolve("report.docx")), "completed inspections retain the Word report");
  }

  @Test
  void reportsUploadDiskFailureAsStorageFailure() throws Exception {
    InspectionException error = assertThrows(InspectionException.class,
        () -> inspectionService.inspect(new FailingMultipartFile(), false));

    assertEquals("STORAGE_FAILED", error.code());
    assertTrue(error.getCause() instanceof IOException);
  }

  @Test
  void preservesUncertainLayoutReviewWithoutWritingAnErrorComment() throws Exception {
    var response = mvc.perform(multipart("/api/inspections")
        .file(new MockMultipartFile("file", "review.docx", MediaType.APPLICATION_OCTET_STREAM_VALUE,
            docxWithReviewOnlyImage()))
        .param("annotate", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotation.available").value(true))
        .andReturn().getResponse().getContentAsString();

    var items = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("annotation").path("items");
    boolean bodyFontWritten = false;
    boolean figureLayoutWritten = false;
    for (var item : items) {
      if ("BODY-FONT".equals(item.path("ruleId").asText())
          && "WRITTEN".equals(item.path("status").asText())) bodyFontWritten = true;
      if ("FIG-02".equals(item.path("ruleId").asText())
          && "WRITTEN".equals(item.path("status").asText())) figureLayoutWritten = true;
    }
    assertTrue(bodyFontWritten);
    assertFalse(figureLayoutWritten);
    var results = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("report").path("results");
    boolean layoutReviewPreserved = false;
    for (var result : results) {
      if ("FIG-02".equals(result.path("ruleId").asText())
          && "REVIEW".equals(result.path("judgement").asText())) layoutReviewPreserved = true;
    }
    assertTrue(layoutReviewPreserved);
  }

  private byte[] validDocx() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
            + "<w:p><w:r><w:rPr><w:rFonts w:ascii=\"Times New Roman\" w:eastAsia=\"黑体\"/><w:sz w:val=\"32\"/><w:b/></w:rPr><w:t>第1章 绪论</w:t></w:r></w:p>"
            + "<w:p><w:r><w:rPr><w:rFonts w:eastAsia=\"黑体\"/><w:sz w:val=\"18\"/></w:rPr><w:t>正文</w:t></w:r></w:p>"
            + "<w:sectPr><w:pgMar w:top=\"1474\" w:right=\"1474\" w:bottom=\"1474\" w:left=\"1474\"/></w:sectPr></w:body></w:document>"));
    Path source = temp.resolve("source.docx");
    pkg.save(source.toFile());
    return java.nio.file.Files.readAllBytes(source);
  }

  private byte[] docxWithReviewOnlyImage() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
            + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
            + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
            + "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>"
            + "<w:p><w:r><w:rPr><w:rFonts w:ascii=\"Times New Roman\" w:eastAsia=\"黑体\"/><w:sz w:val=\"32\"/><w:b/></w:rPr><w:t>第1章 绪论</w:t></w:r></w:p>"
            + "<w:p><w:pPr><w:ind w:firstLine=\"480\"/></w:pPr><w:r><w:rPr><w:rFonts w:ascii=\"Arial\" w:eastAsia=\"宋体\"/><w:sz w:val=\"24\"/></w:rPr><w:t>Body text</w:t></w:r></w:p>"
            + "<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"100\" cy=\"200\"/><wp:docPr id=\"1\" name=\"test\"/><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:nvPicPr><pic:cNvPr id=\"0\" name=\"test\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"100\" cy=\"200\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
            + "<w:sectPr><w:pgMar w:top=\"1474\" w:right=\"1474\" w:bottom=\"1474\" w:left=\"1474\"/></w:sectPr>"
            + "</w:body></w:document>"));
    Path source = temp.resolve("review-only-image.docx");
    pkg.save(source.toFile());
    return java.nio.file.Files.readAllBytes(source);
  }

  private static final class FailingMultipartFile implements MultipartFile {
    @Override public String getName() { return "file"; }
    @Override public String getOriginalFilename() { return "thesis.docx"; }
    @Override public String getContentType() { return MediaType.APPLICATION_OCTET_STREAM_VALUE; }
    @Override public boolean isEmpty() { return false; }
    @Override public long getSize() { return 1; }
    @Override public byte[] getBytes() { return new byte[] {1}; }
    @Override public InputStream getInputStream() { return new java.io.ByteArrayInputStream(new byte[] {1}); }
    @Override public void transferTo(File destination) { throw new AssertionError("Path transfer should be used"); }
    @Override public void transferTo(Path destination) throws IOException { throw new IOException("disk unavailable"); }
  }
}
