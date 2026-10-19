package cn.luwneshacha.platform.engine;

import com.fasterxml.jackson.databind.*;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.stereotype.Component;

@Component
public class HttpAuditEngine implements AuditEngine {
  private static final String SERVICE_ERROR="审核服务异常，请重新提交或联系管理员";
  private final EngineProperties properties;
  private final ObjectMapper json;
  private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
  public HttpAuditEngine(EngineProperties properties,ObjectMapper json) { this.properties=properties; this.json=json; }
  @Override public Result inspect(Path original,Path directory) throws EngineFailure {
    long deadline=System.nanoTime()+properties.timeout().toNanos();
    try {
      String boundary="ThesisPlatform"+UUID.randomUUID();
      byte[] prefix=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"annotate\"\r\n\r\ntrue\r\n--"+boundary
          +"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"thesis.docx\"\r\nContent-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document\r\n\r\n").getBytes(StandardCharsets.UTF_8);
      var body=HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix),HttpRequest.BodyPublishers.ofFile(original),
          HttpRequest.BodyPublishers.ofString("\r\n--"+boundary+"--\r\n"));
      var request=HttpRequest.newBuilder(properties.baseUrl().resolve("/api/inspections"))
          .timeout(properties.timeout()).header("Content-Type","multipart/form-data; boundary="+boundary).POST(body).build();
      HttpResponse<byte[]> response=send(request,limited(HttpResponse.BodyHandlers.ofByteArray(),16L*1024*1024),deadline);
      checkStatus(response.statusCode());
      byte[] bytes=response.body();
      JsonNode root=json.readTree(bytes);
      JsonNode findings=root.path("report").path("results");
      if(!findings.isArray()) throw new EngineFailure(true,SERVICE_ERROR);
      int failures=0;
      for(JsonNode finding:findings) {
        String judgement=finding.path("judgement").asText();
        if(!Set.of("PASS","FAIL","REVIEW","NOT_APPLICABLE").contains(judgement)) throw new EngineFailure(true,SERVICE_ERROR);
        if("FAIL".equals(judgement)) failures++;
      }
      JsonNode annotation=root.path("annotation");
      if(failures>0) {
        JsonNode items=annotation.path("items");
        if(!annotation.path("available").asBoolean()||!items.isArray()||items.isEmpty())
          throw new EngineFailure(false,"检测已完成，但无法生成完整批注文件，请重新提交或联系管理员");
        for(JsonNode item:items) if(!"WRITTEN".equals(item.path("status").asText()))
          throw new EngineFailure(false,"部分格式问题无法写入 Word 批注，请重新提交或联系管理员");
        requireAllFailuresCovered(findings,items);
      }
      Path marked=directory.resolve("marked.docx");
      if(annotation.path("available").asBoolean()) {
        String download=annotation.path("downloadPath").asText();
        if(!download.matches("/api/inspections/[A-Za-z0-9-]+/annotated")) throw new EngineFailure(true,SERVICE_ERROR);
        var output=send(HttpRequest.newBuilder(properties.baseUrl().resolve(download)).timeout(properties.timeout()).GET().build(),
            limited(HttpResponse.BodyHandlers.ofFile(marked),100L*1024*1024),deadline);
        if(output.statusCode()!=200) throw new EngineFailure(true,SERVICE_ERROR);
      } else Files.copy(original,marked);
      Set<String> anchorParts=new LinkedHashSet<>();
      for(JsonNode item:annotation.path("items")) {
        JsonNode anchor=item.path("anchorSource").isObject()?item.path("anchorSource"):item.path("source");
        String part=anchor.path("partName").asText();
        if(!part.isBlank()) anchorParts.add(part);
      }
      MarkedDocumentValidator.validate(original,marked,failures>0,anchorParts);
      if(System.nanoTime()>=deadline) throw new HttpTimeoutException("engine total deadline exceeded");
      return new Result(failures,marked,bytes);
    } catch(EngineFailure error) { throw error; }
    catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new EngineFailure(true,SERVICE_ERROR,error); }
    catch(IOException|RuntimeException error) { throw new EngineFailure(true,SERVICE_ERROR,error); }
  }
  private <T> HttpResponse<T> send(HttpRequest request,HttpResponse.BodyHandler<T> handler,long deadline)
      throws IOException,InterruptedException {
    long remaining=deadline-System.nanoTime();
    if(remaining<=0) throw new HttpTimeoutException("engine total deadline exceeded");
    var response=client.sendAsync(request,handler);
    try { return response.get(remaining,TimeUnit.NANOSECONDS); }
    catch(TimeoutException error) {response.cancel(true);throw new HttpTimeoutException("engine total deadline exceeded");}
    catch(InterruptedException error) {response.cancel(true);throw error;}
    catch(ExecutionException error) {throw new IOException("engine HTTP request failed",error.getCause());}
  }
  private static <T> HttpResponse.BodyHandler<T> limited(HttpResponse.BodyHandler<T> handler,long maximum) {
    return info -> new HttpResponse.BodySubscriber<T>() {
      private final HttpResponse.BodySubscriber<T> delegate=handler.apply(info);
      private Flow.Subscription subscription;
      private long received;
      private boolean ended;
      public CompletionStage<T> getBody() { return delegate.getBody(); }
      public void onSubscribe(Flow.Subscription value) {subscription=value;delegate.onSubscribe(value);}
      public void onNext(List<ByteBuffer> buffers) {
        if(ended) return;
        for(ByteBuffer buffer:buffers) received+=buffer.remaining();
        if(received>maximum) {
          ended=true; subscription.cancel(); delegate.onError(new IOException("engine response exceeds size limit"));
        } else delegate.onNext(buffers);
      }
      public void onError(Throwable error) {if(!ended){ended=true;delegate.onError(error);}}
      public void onComplete() {if(!ended){ended=true;delegate.onComplete();}}
    };
  }
  private static void requireAllFailuresCovered(JsonNode findings,JsonNode annotations) throws EngineFailure {
    Map<List<Object>,JsonNode> representatives=new LinkedHashMap<>();
    for(JsonNode finding:findings) if("FAIL".equals(finding.path("judgement").asText())) {
      JsonNode source=finding.path("source");
      List<Object> key=Arrays.asList(finding.get("ruleId"),source.get("partName"),source.get("elementPath"),finding.get("expected"),finding.get("actual"),finding.get("reasonCode"));
      representatives.putIfAbsent(key,finding);
    }
    Map<List<JsonNode>,Integer> actual=new HashMap<>();
    for(JsonNode item:annotations) actual.merge(Arrays.asList(item.get("ruleId"),item.get("source")),1,Integer::sum);
    for(JsonNode item:representatives.values()) {
      var key=Arrays.asList(item.get("ruleId"),item.get("source"));
      int count=actual.getOrDefault(key,0);
      if(count==0) throw new EngineFailure(false,"部分格式问题缺少批注写入记录，请联系管理员");
      actual.put(key,count-1);
    }
  }
  private static void checkStatus(int status) throws EngineFailure {
    if(status>=200&&status<300) return;
    if(status==400||status==413||status==422)
      throw new EngineFailure(false,"文档损坏或无法解析，请重新保存为 DOCX 后上传");
    throw new EngineFailure(true,SERVICE_ERROR);
  }
  public static void copyLimited(InputStream input,OutputStream output,long limit) throws IOException {
    byte[] buffer=new byte[64*1024]; long total=0; int read;
    while((read=input.read(buffer))!=-1) { total+=read; if(total>limit) throw new IOException("file exceeds maximum size"); output.write(buffer,0,read); }
  }
  @PreDestroy public void close() {client.shutdownNow();}
}
