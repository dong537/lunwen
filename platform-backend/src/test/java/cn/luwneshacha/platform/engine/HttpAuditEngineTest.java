package cn.luwneshacha.platform.engine;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class HttpAuditEngineTest {
  @TempDir Path directory;
  HttpServer server;
  ExecutorService executor;
  final AtomicReference<String> requestBody=new AtomicReference<>();
  final String finding="{\"ruleId\":\"F1\",\"judgement\":\"FAIL\",\"source\":{\"partName\":\"word/document.xml\",\"elementPath\":\"/p[1]\"},\"expected\":\"a\",\"actual\":\"b\",\"reasonCode\":\"wrong\"}";
  final String annotation="{\"ruleId\":\"F1\",\"status\":\"WRITTEN\",\"source\":{\"partName\":\"word/document.xml\",\"elementPath\":\"/p[1]\"}}";
  @BeforeEach void start() throws IOException {
    server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    executor=Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor); server.start();
    respond("/api/inspections/abc/report",200,docx("<w:p><w:r><w:t>检查报告</w:t></w:r></w:p>",false));
  }
  @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }
  HttpAuditEngine engine(Duration timeout) { return new HttpAuditEngine(new EngineProperties(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),timeout),new ObjectMapper()); }
  Path original() throws IOException { Path path=directory.resolve("original.docx"); Files.write(path,docx()); return path; }
  byte[] docx() throws IOException {
    return docx("<w:p><w:r><w:t>论文原文</w:t></w:r></w:p>",false);
  }
  byte[] markedDocx() throws IOException {
    return docx("<w:p><w:r><w:t>论文原文</w:t></w:r><w:r><w:commentReference w:id=\"1\"/></w:r></w:p>",true);
  }
  byte[] docx(String body,boolean comments) throws IOException {
    var bytes=new ByteArrayOutputStream();
    try(var zip=new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
      zip.write("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>".getBytes()); zip.closeEntry();
      zip.putNextEntry(new ZipEntry("_rels/.rels"));
      zip.write("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"doc\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>".getBytes());zip.closeEntry();
      zip.putNextEntry(new ZipEntry("word/document.xml"));
      zip.write(("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\"><w:body>"+body+"</w:body></w:document>").getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
      if(comments) {
        zip.putNextEntry(new ZipEntry("word/comments.xml"));
        zip.write("<w:comments xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:comment w:id=\"1\"><w:p><w:r><w:t>格式问题</w:t></w:r></w:p></w:comment></w:comments>".getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        zip.putNextEntry(new ZipEntry("word/_rels/document.xml.rels"));
        zip.write("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"r1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments\" Target=\"comments.xml\"/></Relationships>".getBytes());zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }
  void respond(String path,int status,byte[] content) {
    server.createContext(path,exchange->{
      byte[] body=exchange.getRequestBody().readAllBytes();
      if(exchange.getRequestMethod().equals("POST")) requestBody.set(new String(body,StandardCharsets.UTF_8));
      exchange.sendResponseHeaders(status,content.length);
      try(var output=exchange.getResponseBody()){output.write(content);}
    });
  }
  String report(String findings,String annotations,String download) {
    return "{\"requestId\":\"abc\",\"report\":{\"results\":["+findings+"]},\"annotation\":{\"available\":true,\"items\":["+annotations+"],\"downloadPath\":\""+download+"\"},\"reportFile\":{\"available\":true,\"downloadPath\":\"/api/inspections/abc/report\"}}";
  }
  String reportResponse(String reportFile) {
    return "{\"requestId\":\"abc\",\"report\":{\"results\":[]},\"annotation\":{\"available\":false},\"reportFile\":"+reportFile+"}";
  }
  @Test void downloadsTheWordReportFromTheSameInspection() throws Exception {
    byte[] reportBytes;
    try(var report=new org.apache.poi.xwpf.usermodel.XWPFDocument();var output=new ByteArrayOutputStream()) {
      report.createParagraph().createRun().setText("检查报告：存在格式问题");
      report.write(output);reportBytes=output.toByteArray();
    }
    respond("/api/inspections",200,reportResponse("{\"available\":true,\"downloadPath\":\"/api/inspections/abc/report\"}").getBytes());
    server.removeContext("/api/inspections/abc/report");
    respond("/api/inspections/abc/report",200,reportBytes);
    var result=engine(Duration.ofSeconds(5)).inspect(original(),directory);
    assertTrue(Files.isRegularFile(directory.resolve("report.docx")),"报告必须从引擎下载");
    assertArrayEquals(reportBytes,Files.readAllBytes(directory.resolve("report.docx")));
    assertEquals(directory.resolve("report.docx"),result.wordReport());
  }
  @Test void requiresAvailableReportFromThisInspection() throws Exception {
    for(String reportFile:java.util.List.of("null","{\"available\":false}",
        "{\"available\":true,\"downloadPath\":\"https://example.com/report\"}",
        "{\"available\":true,\"downloadPath\":\"/api/inspections/other/report\"}",
        "{\"available\":true,\"downloadPath\":\"/api/inspections/abc/../other/report\"}")) {
      respond("/api/inspections",200,reportResponse(reportFile).getBytes());
      assertTrue(assertThrows(EngineFailure.class,()->engine(Duration.ofSeconds(5)).inspect(original(),directory)).retryable());
      server.removeContext("/api/inspections");
      Files.deleteIfExists(directory.resolve("marked.docx"));
      Files.deleteIfExists(directory.resolve("report.docx"));
    }
  }
  @Test void reportHttpFailuresCannotBecomeSuccessfulArtifacts() throws Exception {
    respond("/api/inspections",200,reportResponse("{\"available\":true,\"downloadPath\":\"/api/inspections/abc/report\"}").getBytes());
    for(int status:java.util.List.of(302,404,503)) {
      server.removeContext("/api/inspections/abc/report");
      respond("/api/inspections/abc/report",status,docx());
      assertTrue(assertThrows(EngineFailure.class,()->engine(Duration.ofSeconds(5)).inspect(original(),directory)).retryable());
      Files.deleteIfExists(directory.resolve("marked.docx"));
      Files.deleteIfExists(directory.resolve("report.docx"));
    }
  }
  @Test void reportMustBeReadableNonemptyDocxWithoutEntities() throws Exception {
    respond("/api/inspections",200,reportResponse("{\"available\":true,\"downloadPath\":\"/api/inspections/abc/report\"}").getBytes());
    for(byte[] invalid:java.util.List.of("<html>gateway failure</html>".getBytes(),docx("",false),
        docx("<w:tbl/>",false),replacePart(docx(),"word/document.xml","<broken"),
        replacePart(docx(),"word/document.xml","<!DOCTYPE w:document [<!ENTITY xxe SYSTEM 'file:///nonexistent-sensitive-file'>]><w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body><w:p><w:r><w:t>&xxe;</w:t></w:r></w:p></w:body></w:document>"),
        replacePart(docx(),"[Content_Types].xml","<!DOCTYPE Types [<!ENTITY x 'bad'>]><Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'>&x;</Types>"),
        replacePart(docx(),"_rels/.rels","<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship Id='doc' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument' Target='https://example.com/report' TargetMode='External'/></Relationships>"))) {
      server.removeContext("/api/inspections/abc/report");
      respond("/api/inspections/abc/report",200,invalid);
      assertTrue(assertThrows(EngineFailure.class,()->engine(Duration.ofSeconds(5)).inspect(original(),directory)).retryable());
      Files.deleteIfExists(directory.resolve("marked.docx"));
      Files.deleteIfExists(directory.resolve("report.docx"));
    }
  }
  @Test void reportDownloadSharesTheInspectionDeadline() throws Exception {
    server.removeContext("/api/inspections/abc/report");
    server.createContext("/api/inspections",exchange->{
      exchange.getRequestBody().readAllBytes();
      try {Thread.sleep(400);} catch(InterruptedException error) {Thread.currentThread().interrupt();}
      byte[] body=reportResponse("{\"available\":true,\"downloadPath\":\"/api/inspections/abc/report\"}").getBytes();
      exchange.sendResponseHeaders(200,body.length);
      try(var output=exchange.getResponseBody()) {output.write(body);}
    });
    server.createContext("/api/inspections/abc/report",exchange->{
      exchange.sendResponseHeaders(200,0);
      byte[] body=docx();
      try(var output=exchange.getResponseBody()) {
        output.write(body,0,1);output.flush();
        try {Thread.sleep(500);} catch(InterruptedException error) {Thread.currentThread().interrupt();}
        output.write(body,1,body.length-1);
      }
    });
    Path original=original();
    assertTimeout(Duration.ofSeconds(2),()->assertTrue(assertThrows(EngineFailure.class,
        ()->engine(Duration.ofMillis(700)).inspect(original,directory)).retryable()));
  }
  byte[] replacePart(byte[] original,String part,String replacement) throws IOException {
    var bytes=new ByteArrayOutputStream();
    try(var input=new ZipInputStream(new ByteArrayInputStream(original));var output=new ZipOutputStream(bytes)) {
      ZipEntry entry;
      while((entry=input.getNextEntry())!=null) {
        output.putNextEntry(new ZipEntry(entry.getName()));
        output.write(entry.getName().equals(part)?replacement.getBytes(StandardCharsets.UTF_8):input.readAllBytes());
        output.closeEntry();
      }
    }
    return bytes.toByteArray();
  }
  @Test void countsFailuresAndDownloadsReadableMarkedDocx() throws Exception {
    byte[] marked = markedDocx();
    respond("/api/inspections",200,report(finding,annotation,"/api/inspections/abc/annotated").getBytes());
    respond("/api/inspections/abc/annotated",200,marked);
    var result=engine(Duration.ofSeconds(5)).inspect(original(),directory);
    assertEquals(1,result.problemCount()); assertArrayEquals(marked,Files.readAllBytes(result.marked()));
  }
  @Test void zeroFailuresCopiesOriginalAndSendsMultipartAnnotationFlag() throws Exception {
    respond("/api/inspections",200,reportResponse("{\"available\":true,\"downloadPath\":\"/api/inspections/abc/report\"}").getBytes());
    Path original=original(); var result=engine(Duration.ofSeconds(5)).inspect(original,directory);
    assertEquals(0,result.problemCount()); assertArrayEquals(Files.readAllBytes(original),Files.readAllBytes(result.marked()));
    assertTrue(requestBody.get().contains("name=\"annotate\"\r\n\r\ntrue"));
    assertTrue(requestBody.get().contains("name=\"file\"; filename=\"thesis.docx\""));
  }
  @Test void rejectsUnwrittenAnnotation() throws Exception {
    respond("/api/inspections",200,report(finding,annotation.replace("WRITTEN","UNSUPPORTED"),"/api/inspections/abc/annotated").getBytes());
    assertFalse(assertThrows(EngineFailure.class,()->engine(Duration.ofSeconds(5)).inspect(original(),directory)).retryable());
  }
  @Test void rejectsMissingAnnotationAndForeignDownloadPath() throws Exception {
    respond("/api/inspections",200,report(finding,"","https://example.com/steal").getBytes());
    assertFalse(assertThrows(EngineFailure.class,()->engine(Duration.ofSeconds(5)).inspect(original(),directory)).retryable());
    server.removeContext("/api/inspections");
    respond("/api/inspections",200,report(finding,annotation,"https://example.com/steal").getBytes());
    assertTrue(assertThrows(EngineFailure.class,()->engine(Duration.ofSeconds(5)).inspect(original(),directory)).retryable());
  }
  @Test void uploadResourceLimitDoesNotBlameReadableDocumentForCorruption() throws Exception {
    var failure=inspectionFailure(413,"{\"requestId\":\"test-39\",\"code\":\"RESOURCE_LIMIT\",\"message\":\"file exceeds configured upload limit\"}");
    assertFalse(failure.retryable());
    assertEquals("论文文件或解压后的内容超过审核服务处理上限，请联系管理员检查上传与处理限制",failure.getMessage());
  }
  @ParameterizedTest @ValueSource(ints={400,422})
  void explicitlyInvalidDocxIsPermanentWithSafeInstructions(int status) throws Exception {
    var failure=inspectionFailure(status,"{\"code\":\"INVALID_DOCX\",\"message\":\"unsafe XML private-document-content\"}");
    assertFalse(failure.retryable());
    assertEquals("上传的文件不是可读取的 DOCX，或含有不支持的文档内容，请在 Word 中另存为 DOCX 后重新提交",failure.getMessage());
  }
  @Test void uploadedRuleSpecificationIsDistinguishedFromCorruptDocument() throws Exception {
    var failure=inspectionFailure(400,"{\"code\":\"RULE_SPECIFICATION_DOCUMENT\",\"message\":\"private-document-content\"}");
    assertFalse(failure.retryable());
    assertEquals("上传的是论文格式规范，请上传需要审核的论文 DOCX 文件",failure.getMessage());
  }
  @ParameterizedTest @CsvSource({"400,RESOURCE_LIMIT","422,RESOURCE_LIMIT"})
  void resourceCodeUsesProcessingLimitInstructions(int status,String code) throws Exception {
    var failure=inspectionFailure(status,"{\"code\":\""+code+"\",\"message\":\"ZIP part exceeds configured limit\"}");
    assertFalse(failure.retryable());
    assertEquals("论文文件或解压后的内容超过审核服务处理上限，请联系管理员检查上传与处理限制",failure.getMessage());
  }
  @ParameterizedTest @CsvSource({"400,FORMULA_CONVERSION_FAILED","400,REPORT_GENERATION_FAILED",
      "422,PARSER_FAILED","400,UNKNOWN_CODE","422,UNKNOWN_CODE","500,INVALID_DOCX","503,RESOURCE_LIMIT"})
  void serviceFailuresCannotBeReclassifiedAsDamagedPaper(int status,String code) throws Exception {
    var failure=inspectionFailure(status,"{\"code\":\""+code+"\",\"message\":\"private-document-content\"}");
    assertTrue(failure.retryable());
    assertEquals("审核服务异常，请重新提交或联系管理员",failure.getMessage());
  }
  @ParameterizedTest @ValueSource(strings={"bad document","<html>proxy failure</html>","{", "null", "[]",
      "{}","{\"code\":null}","{\"code\":123}","{\"code\":[\"INVALID_DOCX\"]}",
      "{\"code\":\"INVALID_DOCX private-document-content\"}"})
  void unverifiedErrorBodiesCannotBlamePaper(String response) throws Exception {
    var failure=inspectionFailure(422,response);
    assertTrue(failure.retryable());
    assertEquals("审核服务异常，请重新提交或联系管理员",failure.getMessage());
  }
  @ParameterizedTest @ValueSource(ints={400,403,422,503})
  void unstructuredHttpErrorsRemainRetryableServiceFailures(int status) throws Exception {
    var failure=inspectionFailure(status,"gateway authorization failure");
    assertTrue(failure.retryable());
    assertEquals("审核服务异常，请重新提交或联系管理员",failure.getMessage());
  }
  @Test void gateway413StillExplainsSizeLimitWithoutClaimingCorruption() throws Exception {
    var failure=inspectionFailure(413,"<html>request too large</html>");
    assertFalse(failure.retryable());
    assertEquals("论文文件或解压后的内容超过审核服务处理上限，请联系管理员检查上传与处理限制",failure.getMessage());
  }
  EngineFailure inspectionFailure(int status,String response) throws Exception {
    respond("/api/inspections",status,response.getBytes(StandardCharsets.UTF_8));
    Path original=original();
    return assertThrows(EngineFailure.class,()->engine(Duration.ofSeconds(5)).inspect(original,directory));
  }
  @Test void htmlDownloadCannotBecomeSuccessfulDocx() throws Exception {
    respond("/api/inspections",200,report(finding,annotation,"/api/inspections/abc/annotated").getBytes());
    respond("/api/inspections/abc/annotated",200,"<html>proxy failure</html>".getBytes());
    assertTrue(assertThrows(EngineFailure.class,()->engine(Duration.ofSeconds(5)).inspect(original(),directory)).retryable());
  }
  @Test void corruptWordXmlCannotBecomeSuccessfulDocx() throws Exception {
    var bytes=new ByteArrayOutputStream();
    try(var zip=new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("[Content_Types].xml"));zip.write("<Types/>".getBytes());zip.closeEntry();
      zip.putNextEntry(new ZipEntry("word/document.xml"));zip.write("<w:document>".getBytes());zip.closeEntry();
    }
    respond("/api/inspections",200,report(finding,annotation,"/api/inspections/abc/annotated").getBytes());
    respond("/api/inspections/abc/annotated",200,bytes.toByteArray());
    assertTrue(assertThrows(EngineFailure.class,()->engine(Duration.ofSeconds(5)).inspect(original(),directory)).retryable());
  }
  @Test void zeroFailureResponseCannotAuthorizeAnUnreadableOriginal() throws Exception {
    respond("/api/inspections",200,reportResponse("{\"available\":true,\"downloadPath\":\"/api/inspections/abc/report\"}").getBytes());
    Path invalid=Files.writeString(directory.resolve("original.docx"),"invalid");
    assertTrue(assertThrows(EngineFailure.class,()->engine(Duration.ofSeconds(5)).inspect(invalid,directory)).retryable());
  }
  @Test void writtenClaimWithoutActualWordCommentsIsRejected() throws Exception {
    respond("/api/inspections",200,report(finding,annotation,"/api/inspections/abc/annotated").getBytes());
    respond("/api/inspections/abc/annotated",200,docx());
    assertTrue(assertThrows(EngineFailure.class,()->engine(Duration.ofSeconds(5)).inspect(original(),directory)).retryable());
  }
  @Test void commentsWithoutReferencesAreRejected() throws Exception {
    respond("/api/inspections",200,report(finding,annotation,"/api/inspections/abc/annotated").getBytes());
    respond("/api/inspections/abc/annotated",200,docx("<w:p><w:r><w:t>论文原文</w:t></w:r></w:p>",true));
    assertTrue(assertThrows(EngineFailure.class,()->engine(Duration.ofSeconds(5)).inspect(original(),directory)).retryable());
  }
  @Test void blankMarkedBodyCannotReplaceOriginalContent() throws Exception {
    respond("/api/inspections",200,report(finding,annotation,"/api/inspections/abc/annotated").getBytes());
    respond("/api/inspections/abc/annotated",200,docx("<w:p><w:r><w:commentReference w:id=\"1\"/></w:r></w:p>",true));
    assertTrue(assertThrows(EngineFailure.class,()->engine(Duration.ofSeconds(5)).inspect(original(),directory)).retryable());
  }
  @Test void mathOnlyOriginalCannotBecomeBlankMarkedDocument() throws Exception {
    respond("/api/inspections",200,report(finding,annotation,"/api/inspections/abc/annotated").getBytes());
    respond("/api/inspections/abc/annotated",200,docx("<w:p><w:r><w:commentReference w:id=\"1\"/></w:r></w:p>",true));
    Path original=Files.write(directory.resolve("original.docx"),docx("<w:p><m:oMath><m:r><m:t>x=1</m:t></m:r></m:oMath></w:p>",false));
    assertTrue(assertThrows(EngineFailure.class,()->engine(Duration.ofSeconds(5)).inspect(original,directory)).retryable());
  }
  @Test void responseBodyMustCompleteWithinTotalDeadline() throws Exception {
    server.createContext("/api/inspections",exchange->{
      exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(200,0);
      try(var output=exchange.getResponseBody()) {
        output.write('{'); output.flush();
        try { Thread.sleep(900); } catch(InterruptedException error) {Thread.currentThread().interrupt();}
        output.write("\"report\":{\"results\":[]},\"annotation\":{\"available\":false}}".getBytes());
      }
    });
    Path original=original();
    assertTimeout(Duration.ofSeconds(2),()->assertTrue(assertThrows(EngineFailure.class,
        ()->engine(Duration.ofMillis(300)).inspect(original,directory)).retryable()));
  }
}
