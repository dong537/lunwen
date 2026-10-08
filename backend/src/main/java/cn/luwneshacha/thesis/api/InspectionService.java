package cn.luwneshacha.thesis.api;

import cn.luwneshacha.thesis.inspection.InspectionException;
import cn.luwneshacha.thesis.inspection.InspectionKernel;
import cn.luwneshacha.thesis.inspection.InspectionReport;
import cn.luwneshacha.thesis.reporting.AnnotationReport;
import cn.luwneshacha.thesis.reporting.CommentWriter;
import cn.luwneshacha.thesis.rules.RuleSet;
import cn.luwneshacha.thesis.rules.CheckResult;
import cn.luwneshacha.thesis.rules.Judgement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public final class InspectionService {
  private static final Logger LOG = LoggerFactory.getLogger(InspectionService.class);
  private final InspectionKernel kernel = new InspectionKernel();
  private final CommentWriter commentWriter = new CommentWriter();
  private final Map<String, StoredAnnotation> annotations = new ConcurrentHashMap<>();
  private final Set<String> activeRequests = ConcurrentHashMap.newKeySet();
  private final Object storageLifecycleLock = new Object();
  private final Path storageRoot;
  private final long maxUploadBytes;
  private final Duration annotationRetention;

  public InspectionService(
      @Value("${thesis.storage-root}") String storageRoot,
      @Value("${thesis.max-upload-bytes:16777216}") long maxUploadBytes,
      @Value("${thesis.annotation-retention:PT1H}") Duration annotationRetention) {
    if (maxUploadBytes < 1) throw new IllegalArgumentException("max upload bytes must be positive");
    if (annotationRetention.isNegative() || annotationRetention.isZero())
      throw new IllegalArgumentException("annotation retention must be positive");
    this.storageRoot = Path.of(storageRoot);
    this.maxUploadBytes = maxUploadBytes;
    this.annotationRetention = annotationRetention;
  }

  public InspectionResponse inspect(MultipartFile file, boolean annotate) {
    return inspect(file, annotate, null);
  }

  public InspectionResponse inspect(MultipartFile file, boolean annotate, String requestedRequestId) {
    validate(file);
    String requestId = resolveRequestId(requestedRequestId);
    Path requestDir;
    synchronized (storageLifecycleLock) {
      requestDir = createRequestDir(requestId);
      activeRequests.add(requestId);
    }
    Path source = requestDir.resolve("source.docx");
    try {
      try { file.transferTo(source); }
      catch (IOException error) { throw new InspectionException("STORAGE_FAILED", "cannot store uploaded DOCX", error); }
      InspectionReport report = definitive(kernel.inspect(source, RuleSet.schoolV1()));
      boolean available = false;
      AnnotationReport annotationReport = new AnnotationReport(List.of(), false);
      if (annotate) {
        Path target = requestDir.resolve("annotated.docx");
        annotationReport = commentWriter.writeCopy(source, target, report.sourceSha256(), report.results().stream()
            .filter(InspectionService::needsAnnotation)
            .collect(Collectors.toMap(InspectionService::annotationKey, java.util.function.UnaryOperator.identity(), (first, ignored) -> first, java.util.LinkedHashMap::new))
            .values().stream().toList());
        available = annotationReport.outputWritten();
        if (available) annotations.put(requestId, new StoredAnnotation(requestId, report.sourceSha256(), target));
      }
      return new InspectionResponse(requestId, report.sourceSha256(), report.ruleSetId(), report,
          new InspectionResponse.AnnotationInfo(available, available ? "/api/inspections/" + requestId + "/annotated" : null,
              annotationReport.items()));
    } catch (InspectionException error) {
      throw error;
    } catch (RuntimeException error) {
      throw new InspectionException("PARSER_FAILED", "DOCX parsing failed", error);
    } finally {
      try { Files.deleteIfExists(source); }
      catch (IOException error) { LOG.warn("Unable to remove temporary source for requestId={}", requestId, error); }
      synchronized (storageLifecycleLock) {
        activeRequests.remove(requestId);
        if (!annotations.containsKey(requestId)) deleteRequestDirectory(requestDir, requestId);
      }
    }
  }

  public StoredAnnotation annotation(String requestId) {
    cleanupExpiredArtifacts();
    StoredAnnotation stored = annotations.get(requestId);
    if (stored == null || !Files.isRegularFile(stored.path())) throw new InspectionException("ANNOTATION_NOT_FOUND", "annotation copy does not exist");
    return stored;
  }

  @Scheduled(fixedDelayString = "${thesis.annotation-cleanup-interval:PT5M}")
  public void cleanupExpiredArtifacts() {
    if (!Files.isDirectory(storageRoot, LinkOption.NOFOLLOW_LINKS)) return;
    Instant expiredBefore = Instant.now().minus(annotationRetention);
    try (var entries = Files.list(storageRoot)) {
      for (Path requestDir : entries.toList()) {
        synchronized (storageLifecycleLock) {
          String requestId = requestDir.getFileName().toString();
          if (activeRequests.contains(requestId)) continue;
          try {
            BasicFileAttributes attributes = Files.readAttributes(requestDir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || !attributes.lastModifiedTime().toInstant().isBefore(expiredBefore)) continue;
            deleteRequestDirectory(requestDir, requestId);
            if (!Files.exists(requestDir, LinkOption.NOFOLLOW_LINKS)) annotations.remove(requestId);
          } catch (IOException error) {
            LOG.warn("Unable to inspect expired request directory {}", requestDir.getFileName(), error);
          }
        }
      }
    } catch (IOException error) {
      LOG.warn("Unable to scan annotation storage directory", error);
    }
  }

  private void validate(MultipartFile file) {
    if (file == null || file.isEmpty()) throw new InspectionException("INVALID_DOCX", "file is required");
    String name = file.getOriginalFilename();
    if (name == null || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".docx")) throw new InspectionException("INVALID_DOCX", "input must be a .docx file");
    if (file.getSize() > maxUploadBytes) throw new InspectionException("RESOURCE_LIMIT", "file exceeds configured upload limit");
  }

  private static String resolveRequestId(String requestedRequestId) {
    if (requestedRequestId == null || requestedRequestId.isBlank()) return UUID.randomUUID().toString();
    UUID parsed;
    try { parsed = UUID.fromString(requestedRequestId); }
    catch (IllegalArgumentException error) {
      throw new InspectionException("INVALID_REQUEST_ID", "X-Request-Id must be a UUID");
    }
    if (!parsed.toString().equalsIgnoreCase(requestedRequestId))
      throw new InspectionException("INVALID_REQUEST_ID", "X-Request-Id must be a canonical UUID");
    return parsed.toString();
  }

  private static boolean needsAnnotation(CheckResult result) {
    return result.judgement() != Judgement.PASS;
  }

  /** 对外只给出通过或格式错误；无法证明符合规范的项目按错误处理。 */
  private static InspectionReport definitive(InspectionReport report) {
    var results = report.results().stream().map(result -> result.judgement() == Judgement.REVIEW
        ? new CheckResult(result.ruleId(), result.applicability(), result.executionStatus(), Judgement.FAIL,
            result.expected(), result.actual(), result.reasonCode(), result.source())
        : result).toList();
    return new InspectionReport(report.sourceSha256(), report.ruleSetId(), results, report.diagnostics(),
        report.tables(), report.equations(), report.references(), report.images(), report.pageNumbers(), report.chapterBreaks());
  }

  private static String annotationKey(CheckResult result) {
    // 这里只去掉同位置的同类问题；不同规则交给 CommentWriter 合并正文。
    return result.ruleId() + "|" + result.source().partName() + "|" + result.source().elementPath();
  }

  private Path createRequestDir(String requestId) {
    Path requestDir = storageRoot.resolve(requestId);
    try {
      Files.createDirectories(storageRoot);
      return Files.createDirectory(requestDir);
    } catch (FileAlreadyExistsException error) {
      if (Files.exists(requestDir, LinkOption.NOFOLLOW_LINKS))
        throw new InspectionException("REQUEST_ID_CONFLICT", "requestId has already been used", error);
      throw new InspectionException("STORAGE_FAILED", "cannot create inspection storage", error);
    }
    catch (IOException error) { throw new InspectionException("STORAGE_FAILED", "cannot create inspection storage", error); }
  }

  private void deleteRequestDirectory(Path requestDir, String requestId) {
    try {
      if (!Files.exists(requestDir, LinkOption.NOFOLLOW_LINKS)) return;
      Files.walkFileTree(requestDir, new SimpleFileVisitor<>() {
        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          Files.delete(file);
          return FileVisitResult.CONTINUE;
        }

        @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
          if (error != null) throw error;
          Files.delete(directory);
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException error) {
      LOG.warn("Unable to remove request artifacts for requestId={}", requestId, error);
    }
  }
}
