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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.UUID;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
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
import org.springframework.test.util.ReflectionTestUtils;

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
  void controlledLayoutWithoutProvenMemoryLimitKeepsOpaqueFormulaUnverified() throws Exception {
    Object originalMode = ReflectionTestUtils.getField(inspectionService, "layoutAnalysisEnabled");
    String originalLimiter = System.getProperty("thesis.prlimit");
    ReflectionTestUtils.setField(inspectionService, "layoutAnalysisEnabled", true);
    System.setProperty("thesis.prlimit", temp.resolve("missing-prlimit").toString());
    try {
      String response = mvc.perform(multipart("/api/inspections")
          .file(new MockMultipartFile("file", "opaque.docx",
              MediaType.APPLICATION_OCTET_STREAM_VALUE, validDocxWithOpaqueFormula()))
          .param("annotate", "false"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.report.layoutEvidence.status").value("unavailable"))
          .andExpect(jsonPath("$.report.equations[0].type").value("OLE"))
          .andReturn().getResponse().getContentAsString();
      var report = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("report");
      assertEquals(report.path("sourceSha256").asText(),
          report.path("layoutEvidence").path("sourceSha256").asText());
      boolean unknownFormula = false;
      for (var run : report.path("ruleExecution").path("ruleRuns"))
        if ("EQ-06".equals(run.path("ruleId").asText()) && run.path("unknownCount").asInt() > 0)
          unknownFormula = true;
      assertTrue(unknownFormula, "opaque formula internals must remain unknown");
    } finally {
      ReflectionTestUtils.setField(inspectionService, "layoutAnalysisEnabled", originalMode);
      if (originalLimiter == null) System.clearProperty("thesis.prlimit");
      else System.setProperty("thesis.prlimit", originalLimiter);
    }
  }

  @Test
  void ordinaryModeStillInspectsWithoutAProcessLimiter() throws Exception {
    Object originalMode = ReflectionTestUtils.getField(inspectionService, "layoutAnalysisEnabled");
    String originalLimiter = System.getProperty("thesis.prlimit");
    ReflectionTestUtils.setField(inspectionService, "layoutAnalysisEnabled", false);
    System.setProperty("thesis.prlimit", temp.resolve("missing-prlimit").toString());
    try {
      mvc.perform(multipart("/api/inspections")
          .file(new MockMultipartFile("file", "ordinary.docx",
              MediaType.APPLICATION_OCTET_STREAM_VALUE, validDocx()))
          .param("annotate", "false"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.report.ruleSetId").value("school-v1"));
    } finally {
      ReflectionTestUtils.setField(inspectionService, "layoutAnalysisEnabled", originalMode);
      if (originalLimiter == null) System.clearProperty("thesis.prlimit");
      else System.setProperty("thesis.prlimit", originalLimiter);
    }
  }

  @Test
  void ordinaryModeWithOpaqueFormulaUsesOriginalAndNeverStartsConverter() throws Exception {
    Object originalMode = ReflectionTestUtils.getField(inspectionService, "layoutAnalysisEnabled");
    String originalLimiter = System.getProperty("thesis.prlimit");
    String originalOffice = System.getProperty("thesis.soffice");
    ReflectionTestUtils.setField(inspectionService, "layoutAnalysisEnabled", false);
    System.setProperty("thesis.prlimit", temp.resolve("missing-prlimit").toString());
    System.setProperty("thesis.soffice", temp.resolve("missing-office").toString());
    try {
      byte[] original = validDocxWithOpaqueFormula();
      String response = mvc.perform(multipart("/api/inspections")
          .file(new MockMultipartFile("file", "opaque-ordinary.docx",
              MediaType.APPLICATION_OCTET_STREAM_VALUE, original))
          .param("annotate", "false"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.report.equations[0].type").value("OLE"))
          .andReturn().getResponse().getContentAsString();
      var identity = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);
      String hash = java.util.HexFormat.of().formatHex(
          java.security.MessageDigest.getInstance("SHA-256").digest(original));
      assertEquals(hash, identity.path("sourceSha256").asText());
      assertEquals(hash, identity.path("report").path("sourceSha256").asText());
      assertFalse(Files.exists(STORAGE_ROOT.resolve(identity.path("requestId").asText())
          .resolve("formula-memory-limit.log")), "normal inspection must not start formula conversion");
      boolean unknownFormula = false;
      for (var run : new com.fasterxml.jackson.databind.ObjectMapper().readTree(response)
          .path("report").path("ruleExecution").path("ruleRuns"))
        if ("EQ-06".equals(run.path("ruleId").asText()) && run.path("unknownCount").asInt() > 0)
          unknownFormula = true;
      assertTrue(unknownFormula);
    } finally {
      ReflectionTestUtils.setField(inspectionService, "layoutAnalysisEnabled", originalMode);
      if (originalLimiter == null) System.clearProperty("thesis.prlimit");
      else System.setProperty("thesis.prlimit", originalLimiter);
      if (originalOffice == null) System.clearProperty("thesis.soffice");
      else System.setProperty("thesis.soffice", originalOffice);
    }
  }

  @Test
  void opaqueFormulaKeepsOriginalStylingWithoutRunningAConverter() throws Exception {
    byte[] original = validDocxWithOpaqueFormula();
    String priorLimiter = System.getProperty("thesis.prlimit");
    System.setProperty("thesis.prlimit", temp.resolve("missing-prlimit").toString());
    try {
      String response = mvc.perform(multipart("/api/inspections")
          .file(new MockMultipartFile("file", "opaque-original.docx",
              MediaType.APPLICATION_OCTET_STREAM_VALUE, original))
          .param("annotate", "true"))
          .andExpect(status().isOk())
          .andReturn().getResponse().getContentAsString();
      var result = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);
      String originalSha = java.util.HexFormat.of().formatHex(
          java.security.MessageDigest.getInstance("SHA-256").digest(original));
      assertEquals(originalSha, result.path("sourceSha256").asText());
      assertEquals(originalSha, result.path("report").path("sourceSha256").asText());
      String requestId = result.path("requestId").asText();
      assertEquals(originalSha, inspectionService.report(requestId).sourceSha256());
      assertFalse(Files.exists(STORAGE_ROOT.resolve(requestId).resolve("formula-memory-limit.log")));
      assertFalse(Files.exists(STORAGE_ROOT.resolve(requestId).resolve("formula-conversion-odt")));
      byte[] marked = Files.readAllBytes(STORAGE_ROOT.resolve(requestId).resolve("annotated.docx"));
      String documentXml = partText(marked, "word/document.xml");
      assertTrue(documentXml.contains("黑体"), "annotated copy must retain original run styling");
    } finally {
      if (priorLimiter == null) System.clearProperty("thesis.prlimit");
      else System.setProperty("thesis.prlimit", priorLimiter);
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
        assertTrue(document.contains("论文格式检查汇总"));
        assertTrue(document.contains("问题数量"));
        assertFalse(document.contains("批注"));
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

  private byte[] validDocxWithOpaqueFormula() throws Exception {
    byte[] plain = validDocx();
    var output = new ByteArrayOutputStream();
    try (var input = new ZipInputStream(new ByteArrayInputStream(plain));
         var zip = new ZipOutputStream(output)) {
      ZipEntry entry;
      while ((entry = input.getNextEntry()) != null) {
        zip.putNextEntry(new ZipEntry(entry.getName()));
        byte[] content = input.readAllBytes();
        if (entry.getName().equals("word/document.xml")) {
          String xml = new String(content, StandardCharsets.UTF_8);
          String ole = "<w:p><w:r><w:object><o:OLEObject "
              + "xmlns:o=\"urn:schemas-microsoft-com:office:office\" "
              + "ProgID=\"Equation.3\"/></w:object></w:r></w:p>";
          assertTrue(xml.contains("<w:sectPr"));
          content = xml.replaceFirst("<w:sectPr", ole + "<w:sectPr")
              .getBytes(StandardCharsets.UTF_8);
        }
        zip.write(content);
        zip.closeEntry();
      }
    }
    return output.toByteArray();
  }

  private static String partText(byte[] packageBytes, String partName) throws IOException {
    try (var input = new ZipInputStream(new ByteArrayInputStream(packageBytes))) {
      ZipEntry entry;
      while ((entry = input.getNextEntry()) != null) {
        if (partName.equals(entry.getName()))
          return new String(input.readAllBytes(), StandardCharsets.UTF_8);
      }
    }
    throw new IOException("missing package part: " + partName);
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
