package cn.luwneshacha.thesis.api;

import cn.luwneshacha.thesis.document.DocxReader;
import cn.luwneshacha.thesis.document.RawDocx;
import cn.luwneshacha.thesis.inspection.InspectionException;
import cn.luwneshacha.thesis.inspection.InspectionKernel;
import cn.luwneshacha.thesis.inspection.InspectionReport;
import cn.luwneshacha.thesis.reporting.AnnotationReport;
import cn.luwneshacha.thesis.reporting.CommentWriter;
import cn.luwneshacha.thesis.rules.RuleSet;
import cn.luwneshacha.thesis.rules.CheckResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public final class InspectionService {
  private final InspectionKernel kernel = new InspectionKernel();
  private final DocxReader reader = new DocxReader();
  private final CommentWriter commentWriter = new CommentWriter();
  private final Map<String, StoredAnnotation> annotations = new ConcurrentHashMap<>();
  private final Path storageRoot;
  private final long maxUploadBytes;

  public InspectionService(
      @Value("${thesis.storage-root}") Path storageRoot,
      @Value("${thesis.max-upload-bytes:16777216}") long maxUploadBytes) {
    if (maxUploadBytes < 1) throw new IllegalArgumentException("max upload bytes must be positive");
    this.storageRoot = storageRoot;
    this.maxUploadBytes = maxUploadBytes;
  }

  public InspectionResponse inspect(MultipartFile file, boolean annotate) {
    validate(file);
    String requestId = UUID.randomUUID().toString();
    Path requestDir = createRequestDir(requestId);
    Path source = requestDir.resolve("source.docx");
    try {
      file.transferTo(source);
      RawDocx raw = reader.read(source);
      InspectionReport report = kernel.inspect(source, RuleSet.schoolV1());
      boolean available = false;
      if (annotate) {
        Path target = requestDir.resolve("annotated.docx");
        AnnotationReport annotation = commentWriter.writeCopy(source, target, raw.sourceSha256(), report.results().stream()
            .filter(InspectionService::needsAnnotation)
            .toList());
        available = annotation.outputWritten();
        if (available) annotations.put(requestId, new StoredAnnotation(requestId, raw.sourceSha256(), target));
      }
      return new InspectionResponse(requestId, report.sourceSha256(), report.ruleSetId(), report,
          new InspectionResponse.AnnotationInfo(available, available ? "/api/inspections/" + requestId + "/annotated" : null));
    } catch (InspectionException error) {
      throw error;
    } catch (IOException | RuntimeException error) {
      throw new InspectionException("PARSER_FAILED", "DOCX parsing failed");
    } finally {
      try { Files.deleteIfExists(source); } catch (IOException ignored) { }
    }
  }

  public StoredAnnotation annotation(String requestId) {
    StoredAnnotation stored = annotations.get(requestId);
    if (stored == null || !Files.isRegularFile(stored.path())) throw new InspectionException("ANNOTATION_NOT_FOUND", "annotation copy does not exist");
    return stored;
  }

  private void validate(MultipartFile file) {
    if (file == null || file.isEmpty()) throw new InspectionException("INVALID_DOCX", "file is required");
    String name = file.getOriginalFilename();
    if (name == null || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".docx")) throw new InspectionException("INVALID_DOCX", "input must be a .docx file");
    if (file.getSize() > maxUploadBytes) throw new InspectionException("RESOURCE_LIMIT", "file exceeds configured upload limit");
  }

  private static boolean needsAnnotation(CheckResult result) {
    return result.judgement() != cn.luwneshacha.thesis.rules.Judgement.PASS
        || result.applicability() == cn.luwneshacha.thesis.rules.Applicability.UNKNOWN
        || result.executionStatus() != cn.luwneshacha.thesis.rules.ExecutionStatus.EXECUTED;
  }

  private Path createRequestDir(String requestId) {
    try { return Files.createDirectories(storageRoot.resolve(requestId)); }
    catch (IOException error) { throw new InspectionException("STORAGE_FAILED", "cannot create inspection storage"); }
  }
}
