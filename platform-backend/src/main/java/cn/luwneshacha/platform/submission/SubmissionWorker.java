package cn.luwneshacha.platform.submission;

import cn.luwneshacha.platform.engine.*;
import cn.luwneshacha.platform.account.User;
import cn.luwneshacha.platform.account.UserRepository;
import cn.luwneshacha.platform.report.PdfSummaryReportGenerator;
import cn.luwneshacha.platform.report.PdfSummaryReportWriter;
import cn.luwneshacha.platform.storage.ObjectStorage;
import cn.luwneshacha.platform.storage.CosObjectStorage;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @ConditionalOnProperty(name="platform.worker.enabled",havingValue="true",matchIfMissing=true)
public class SubmissionWorker {
  private static final Logger log=LoggerFactory.getLogger(SubmissionWorker.class);
  /** One original download plus marked DOCX, Word report, JSON and PDF uploads. */
  private static final int STORAGE_OPERATION_COUNT = 5;
  private final SubmissionTasks tasks;
  private final SubmissionRepository repository;
  private final ObjectStorage storage;
  private final AuditEngine engine;
  private final UserRepository users;
  private final PdfSummaryReportGenerator pdfWriter;
  private long cleanupCursor;
  @Autowired public SubmissionWorker(SubmissionTasks tasks,SubmissionRepository repository,ObjectStorage storage,AuditEngine engine,
      UserRepository users, PdfSummaryReportGenerator pdfWriter, EngineProperties engineProperties,WorkerProperties workerProperties) {
    this(tasks,repository,storage,engine,users,pdfWriter);
    validateLease(engineProperties, workerProperties);
  }
  static void validateLease(EngineProperties engineProperties, WorkerProperties workerProperties) {
    var required=engineProperties.timeout()
        .plus(CosObjectStorage.REQUEST_BUDGET.multipliedBy(STORAGE_OPERATION_COUNT)).plusMinutes(1);
    if(workerProperties.lease().compareTo(required)<=0)
      throw new IllegalArgumentException("platform.worker.lease 必须大于审核引擎总超时 + 26分钟存储及提交预算");
  }
  public SubmissionWorker(SubmissionTasks tasks,SubmissionRepository repository,ObjectStorage storage,AuditEngine engine,
      UserRepository users, PdfSummaryReportGenerator pdfWriter) {
    this.tasks=tasks; this.repository=repository; this.storage=storage; this.engine=engine;
    this.users=users; this.pdfWriter=pdfWriter;
  }
  @Scheduled(fixedDelayString="${platform.worker.poll-delay-ms:3000}")
  public void poll() {
    try {
      tasks.recoverExhausted(Instant.now());
      tasks.claim(Instant.now()).ifPresent(this::process);
    } catch(RuntimeException error) { log.error("submission_worker_poll_failed",error); }
  }
  public void process(SubmissionTasks.Claim claim) {
    Path directory=null;
    boolean succeeded=false;
    try {
      directory=Files.createTempDirectory("thesis-attempt-");
      Path input=directory.resolve("original.docx");
      try(var source=storage.get(claim.directory()+"/original.docx");var output=Files.newOutputStream(input)) {
        HttpAuditEngine.copyLimited(source.stream(),output,SubmissionService.MAX_BYTES);
      }
      var result=engine.inspect(input,directory);
      try(var content=Files.newInputStream(result.marked())) {
        storage.put(claim.attemptPrefix()+"marked.docx",content,Files.size(result.marked()),SubmissionService.DOCX);
      }
      try(var content=Files.newInputStream(result.reportDocx())) {
        storage.put(claim.attemptPrefix()+"report.docx",content,Files.size(result.reportDocx()),SubmissionService.DOCX);
      }
      try(var content=new ByteArrayInputStream(result.reportJson())) {
        storage.put(claim.attemptPrefix()+"report.json",content,result.reportJson().length,"application/json");
      }
      if (pdfWriter == null || users == null) throw new IllegalStateException("PDF 审核结果单生成器未配置");
      Submission submission = repository.findById(claim.id()).orElseThrow();
      User student = users.findById(submission.getStudentId()).orElseThrow();
      User teacher = student.getTeacherId() == null ? null : users.findById(student.getTeacherId()).orElse(null);
      long submissionNumber = repository.submissionNumbers(java.util.List.of(submission.getId())).stream()
          .findFirst().map(row -> ((Number) row[1]).longValue()).orElseThrow();
      Instant finishedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
      Path pdf = directory.resolve("report.pdf");
      pdfWriter.write(result.reportJson(), new PdfSummaryReportWriter.Metadata(
          submission.getOriginalFilename(), submissionNumber, finishedAt,
          new PdfSummaryReportWriter.StudentInfo(student.getName(), student.getUsername(), student.getMajor(),
              teacher == null ? null : teacher.getName())), pdf);
      if (!Files.isRegularFile(pdf) || Files.size(pdf) == 0) {
        throw new IOException("PDF 审核结果单生成器未生成文件");
      }
      try(var content=Files.newInputStream(pdf)) {
        storage.put(claim.attemptPrefix()+"report.pdf",content,Files.size(pdf),SubmissionService.PDF);
      }
      succeeded=tasks.succeed(claim,result.problemCount(),true,finishedAt);
      if(!succeeded) log.warn("submission_attempt_fenced submission={} attempt={}",claim.id(),claim.attempt());
    } catch(EngineFailure error) {
      log.warn("submission_engine_failed submission={} attempt={} retryable={}",claim.id(),claim.attempt(),error.retryable(),error);
      recordFailure(claim,error.retryable(),error.getMessage());
    } catch(IOException|RuntimeException error) {
      log.warn("submission_attempt_failed submission={} attempt={}",claim.id(),claim.attempt(),error);
      recordFailure(claim,true,"审核服务异常，请重新提交或联系管理员");
    } finally {
      if(!succeeded) cleanupAttempt(claim);
      if(directory!=null) try(var files=Files.walk(directory)) {
        for(Path path:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
      } catch(IOException error) { log.error("submission_local_cleanup_failed submission={}",claim.id(),error); }
    }
  }
  /** The persistent submission row makes deletion retryable after process restart. */
  @Scheduled(fixedDelayString="${platform.worker.cleanup-delay-ms:60000}")
  public void compensateCleanup() {
    try {
      var page=repository.findByIdGreaterThanOrderById(cleanupCursor,PageRequest.of(0,100));
      if(page.isEmpty()) {cleanupCursor=0;return;}
      for(Submission item:page) {
        if(item.getStatus()==SubmissionStatus.FAILED) cleanup(item.getStorageDir()+"/",item.getId());
        else {
          int before = item.getAttempts() + (item.getStatus()==SubmissionStatus.PENDING ? 1 : 0);
          for(int attempt=1;attempt<before;attempt++) cleanup(item.getStorageDir()+"/attempt-"+attempt+"/",item.getId());
        }
        cleanupCursor=item.getId();
      }
    } catch(RuntimeException error) {log.error("submission_cleanup_scan_failed",error);}
  }
  private void recordFailure(SubmissionTasks.Claim claim,boolean retryable,String message) {
    try { tasks.fail(claim,retryable,message,Instant.now()); }
    catch(RuntimeException error) {log.error("submission_failure_state_pending submission={} attempt={}",claim.id(),claim.attempt(),error);}
  }
  private void cleanupAttempt(SubmissionTasks.Claim claim) {
    try {
      repository.findById(claim.id()).ifPresent(item -> {
        if(item.getStatus()==SubmissionStatus.FAILED) cleanup(item.getStorageDir()+"/",item.getId());
        else if(item.getAttempts()>claim.attempt()
            || item.getStatus()==SubmissionStatus.PENDING && item.getAttempts()==claim.attempt())
          cleanup(claim.attemptPrefix(),claim.id());
      });
    } catch(RuntimeException error) {
      // Unknown commit outcome: preserve artifacts until persistent state is readable again.
      log.error("submission_cleanup_state_unavailable submission={} attempt={}",claim.id(),claim.attempt(),error);
    }
  }
  private void cleanup(String prefix,long id) {
    try {storage.deletePrefix(prefix);}
    catch(IOException|RuntimeException error) {log.error("submission_cleanup_pending submission={} prefix={}",id,prefix,error);}
  }
}
