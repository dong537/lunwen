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
      zip.write("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>".getBytes()); zip.closeEntry();
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
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
      exchange.sendResponseHeaders(status,content.length);
      try(var output=exchange.getResponseBody()){output.write(content);}
    });
  }
  String report(String findings,String annotations,String download) {
    return "{\"report\":{\"results\":["+findings+"]},\"annotation\":{\"available\":true,\"items\":["+annotations+"],\"downloadPath\":\""+download+"\"}}";
  }
  @Test void countsFailuresAndDownloadsReadableMarkedDocx() throws Exception {
    respond("/api/inspections",200,report(finding,annotation,"/api/inspections/abc/annotated").getBytes());
    respond("/api/inspections/abc/annotated",200,markedDocx());
    var result=engine(Duration.ofSeconds(5)).inspect(original(),directory);
    assertEquals(1,result.problemCount()); assertArrayEquals(markedDocx(),Files.readAllBytes(result.marked()));
  }
  @Test void zeroFailuresCopiesOriginalAndSendsMultipartAnnotationFlag() throws Exception {
    respond("/api/inspections",200,"{\"report\":{\"results\":[]},\"annotation\":{\"available\":false}}".getBytes());
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
  @Test void malformedDocumentIsPermanentButServerFailureRetries() throws Exception {
    respond("/api/inspections",422,"bad document".getBytes());
    assertFalse(assertThrows(EngineFailure.class,()->engine(Duration.ofSeconds(5)).inspect(original(),directory)).retryable());
    server.removeContext("/api/inspections"); respond("/api/inspections",503,"unavailable".getBytes());
    assertTrue(assertThrows(EngineFailure.class,()->engine(Duration.ofSeconds(5)).inspect(original(),directory)).retryable());
    server.removeContext("/api/inspections"); respond("/api/inspections",403,"gateway authorization failure".getBytes());
    assertTrue(assertThrows(EngineFailure.class,()->engine(Duration.ofSeconds(5)).inspect(original(),directory)).retryable());
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
    respond("/api/inspections",200,"{\"report\":{\"results\":[]},\"annotation\":{\"available\":false}}".getBytes());
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
