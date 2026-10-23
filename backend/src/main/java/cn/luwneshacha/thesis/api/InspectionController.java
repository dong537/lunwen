package cn.luwneshacha.thesis.api;

import java.nio.file.Files;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@CrossOrigin(originPatterns = {
    "http://localhost:*",
    "http://127.0.0.1:*",
    "http://124.223.86.157",
    "https://124.223.86.157"
})
@RequestMapping("/api/inspections")
public final class InspectionController {
  private static final MediaType DOCX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
  private final InspectionService service;

  public InspectionController(InspectionService service) { this.service = service; }

  @GetMapping("/health")
  public ResponseEntity<String> health() {
    return ResponseEntity.ok("{\"status\":\"UP\"}");
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public InspectionResponse inspect(
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "annotate", defaultValue = "false") boolean annotate,
      @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
    return service.inspect(file, annotate, requestId);
  }

  @GetMapping("/{requestId}/annotated")
  public ResponseEntity<FileSystemResource> download(@PathVariable("requestId") String requestId) {
    var stored = service.annotation(requestId);
    var resource = new FileSystemResource(stored.path());
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(DOCX);
    headers.setContentDisposition(ContentDisposition.attachment().filename("thesis-annotated-" + requestId + ".docx").build());
    headers.setContentLength(Files.exists(stored.path()) ? stored.path().toFile().length() : 0);
    return ResponseEntity.ok().headers(headers).body(resource);
  }
  @GetMapping("/{requestId}/report")
  public ResponseEntity<FileSystemResource> report(@PathVariable("requestId") String requestId) {
    var stored = service.report(requestId);
    var resource = new FileSystemResource(stored.path());
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(DOCX);
    headers.setContentDisposition(ContentDisposition.attachment().filename("thesis-report-" + requestId + ".docx").build());
    headers.setContentLength(Files.exists(stored.path()) ? stored.path().toFile().length() : 0);
    return ResponseEntity.ok().headers(headers).body(resource);
  }
}
