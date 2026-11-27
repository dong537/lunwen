package cn.luwneshacha.platform.submission;

import static org.junit.jupiter.api.Assertions.*;
import cn.luwneshacha.platform.account.*;
import cn.luwneshacha.platform.auth.CurrentUser;
import cn.luwneshacha.platform.common.ApiException;
import cn.luwneshacha.platform.storage.ObjectPresigner;
import cn.luwneshacha.platform.storage.ObjectStorage;
import cn.luwneshacha.platform.engine.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=validate", "logging.level.org.hibernate.SQL=OFF"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = SubmissionIntegrationTest.TestApplication.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class SubmissionIntegrationTest {
  @SpringBootConfiguration @EnableAutoConfiguration
  @EntityScan(basePackageClasses = {User.class, Submission.class})
  @EnableJpaRepositories(basePackageClasses = {UserRepository.class, SubmissionRepository.class})
  static class TestApplication {}

  @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
  @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }
  @Autowired SubmissionRepository submissions;
  @Autowired SubmissionRuleIssueRepository ruleIssues;
  @Autowired UserRepository users;
  @Autowired PlatformTransactionManager transactions;
  @Autowired javax.sql.DataSource dataSource;
  final MemoryBoundary storage = new MemoryBoundary();
  final FakePresigner presigner = new FakePresigner();
  SubmissionService service;
  SubmissionTasks tasks;
  User student, other, teacher, admin;

  @BeforeEach void setup() {
    ruleIssues.deleteAllInBatch(); submissions.deleteAllInBatch(); users.deleteAllInBatch(); storage.data.clear();
    teacher = users.save(new User("teacher", "hash", Role.TEACHER, "教师", null, null, false));
    student = users.save(new User("student", "hash", Role.STUDENT, "学生", "计算机", teacher.getId(), false));
    other = users.save(new User("other", "hash", Role.STUDENT, "其他", null, null, false));
    admin = users.save(new User("admin", "hash", Role.ADMIN, "管理员", null, null, false));
    service = new SubmissionService(submissions, users, storage, transactions, presigner,
        new TransferProperties(TransferProperties.Mode.DIRECT,600));
    tasks = new SubmissionTasks(submissions, ruleIssues, transactions, Duration.ofMinutes(15));
  }

  @Test void rejectsInvalidFilesWithoutRowsOrObjects() {
    for (MockMultipartFile file : List.of(file("a.pdf", new byte[]{1}), file("a.docx", new byte[0])))
      assertThrows(ApiException.class, () -> service.upload(actor(student), file));
    assertEquals(0, submissions.count()); assertTrue(storage.data.isEmpty());
  }

  @Test void persistsPendingAndRejectsCrossAccountOrAdminAccess() {
    var view = service.upload(actor(student), file("论文.docx", new byte[]{1, 2}));
    assertEquals(SubmissionStatus.PENDING, view.status()); assertNull(view.problemCount());
    assertTrue(view.canDownloadOriginal()); assertFalse(view.canDownloadMarked());
    long id = Long.parseLong(view.id());
    assertEquals(view.id(), service.detail(actor(teacher), id).id());
    assertThrows(ApiException.class, () -> service.detail(actor(other), id));
    assertThrows(ApiException.class, () -> service.prepareDownload(actor(admin), id, "original"));
    assertThrows(ApiException.class, () -> service.prepareDownload(actor(student), id, "marked"));
    assertThrows(ApiException.class, () -> service.upload(actor(teacher), file("a.docx", new byte[]{1})));
    student.updateProfile("学生", "计算机", null); users.save(student);
    assertThrows(ApiException.class, () -> service.detail(actor(teacher), id));
  }

  @Test void collegeAdministratorReadsOwnCollegePapersAndDownloadPlanRechecksCurrentOwnership() throws Exception {
    User adviser = users.save(new User("college_adviser", "hash", Role.ADMIN, "院管导师", null, null,
        College.INFORMATION_ENGINEERING.name(), false));
    student.updateProfile("学生", "计算机", adviser.getId(), College.INFORMATION_ENGINEERING.name());
    users.saveAndFlush(student);
    long id = Long.parseLong(service.upload(actor(student), file("paper.docx", new byte[]{1})).id());
    assertEquals(Long.toString(id), service.detail(actor(adviser), id).id());
    assertEquals(1, service.students(actor(adviser), "", 1, 20).total());
    assertEquals(1, service.studentSubmissions(actor(adviser), student.getId(), 1, 20).total());
    var plan = service.prepareDownload(actor(adviser), id, "original");
    try (var downloaded = service.openDownload(plan)) { assertEquals(1, downloaded.size()); }
    assertNotNull(service.prepareDownloadUrl(actor(adviser), id, "original").url());
    student.updateProfile("学生", "计算机", adviser.getId(), College.EDUCATION_ARTS.name());
    users.saveAndFlush(student);
    assertEquals(0, service.students(actor(adviser), "", 1, 20).total());
    assertThrows(ApiException.class, () -> service.detail(actor(adviser), id));
    assertThrows(ApiException.class, () -> service.student(actor(adviser), student.getId()));
    assertThrows(ApiException.class, () -> service.openDownload(plan));
    assertThrows(ApiException.class, () -> service.prepareDownloadUrl(actor(adviser), id, "original"));
    student.updateProfile("学生", "计算机", teacher.getId(), College.INFORMATION_ENGINEERING.name());
    users.saveAndFlush(student);
    assertThrows(ApiException.class, () -> service.detail(actor(adviser), id));
    assertThrows(ApiException.class, () -> service.openDownload(plan));
    assertThrows(ApiException.class, () -> service.students(actor(admin), "", 1, 20));
  }

  @Test void expiresOldAttemptAndFencesItsCompletion() {
    var submitted = service.upload(actor(student), file("a.docx", new byte[]{1}));
    Instant now = Instant.now();
    var first = tasks.claim(now).orElseThrow();
    assertTrue(tasks.claim(now).isEmpty());
    var second = tasks.claim(now.plusSeconds(901)).orElseThrow();
    assertEquals(2, second.attempt());
    assertFalse(tasks.succeed(first, 0, now.plusSeconds(902)));
    assertTrue(tasks.succeed(second, 7, now.plusSeconds(903)));
    assertFalse(tasks.fail(first, false, "过期尝试失败", now.plusSeconds(904)));
    assertEquals(7, service.detail(actor(student), Long.parseLong(submitted.id())).problemCount());
  }

  @Test void historicalSuccessBackfillIndexesOnlyOnceAndPreservesZeroIssueReport() {
    long id = Long.parseLong(service.upload(actor(student), file("a.docx", new byte[]{1})).id());
    Instant now = Instant.now();
    var claim = tasks.claim(now).orElseThrow();
    assertTrue(tasks.succeed(claim, 2, now.plusSeconds(1)));
    storage.data.put(claim.attemptPrefix() + "report.json", reportWithFailures(2));
    var backfill = new RuleIssueBackfill(submissions, ruleIssues, storage, transactions);
    backfill.processBatch(now.plusSeconds(2));
    var indexed = submissions.findById(id).orElseThrow();
    assertEquals(RuleIndexStatus.READY, indexed.getRuleIndexStatus());
    assertEquals(1, indexed.getRuleIndexAttempts());
    var issueRows = ruleIssues.findBySubmissionIdIn(List.of(id));
    assertEquals(1, issueRows.size());
    assertEquals(2, issueRows.get(0).getIssueCount());
    backfill.processBatch(now.plusSeconds(3));
    assertEquals(1, ruleIssues.findBySubmissionIdIn(List.of(id)).size());
    assertEquals(1, submissions.findById(id).orElseThrow().getRuleIndexAttempts());

    long zeroId = Long.parseLong(service.upload(actor(other), file("b.docx", new byte[]{1})).id());
    var zeroClaim = tasks.claim(now.plusSeconds(4)).orElseThrow();
    assertTrue(tasks.succeed(zeroClaim, 0, now.plusSeconds(5)));
    storage.data.put(zeroClaim.attemptPrefix() + "report.json", reportWithFailures(0));
    backfill.processBatch(now.plusSeconds(6));
    assertEquals(RuleIndexStatus.READY, submissions.findById(zeroId).orElseThrow().getRuleIndexStatus());
    assertTrue(ruleIssues.findBySubmissionIdIn(List.of(zeroId)).isEmpty());
  }

  @Test void unavailableHistoricalReportNeverPretendsToHaveZeroIssues() {
    long id = Long.parseLong(service.upload(actor(student), file("a.docx", new byte[]{1})).id());
    Instant now = Instant.now();
    var claim = tasks.claim(now).orElseThrow();
    assertTrue(tasks.succeed(claim, 3, now.plusSeconds(1)));
    var backfill = new RuleIssueBackfill(submissions, ruleIssues, storage, transactions);
    backfill.processBatch(now.plusSeconds(2));
    assertEquals(RuleIndexStatus.UNAVAILABLE, submissions.findById(id).orElseThrow().getRuleIndexStatus());
    backfill.processBatch(now.plusSeconds(63));
    backfill.processBatch(now.plusSeconds(184));
    var unresolved = submissions.findById(id).orElseThrow();
    assertEquals(RuleIndexStatus.UNAVAILABLE, unresolved.getRuleIndexStatus());
    assertEquals(1, unresolved.getRuleIndexAttempts());
    assertEquals(3, unresolved.getProblemCount());
    assertTrue(ruleIssues.findBySubmissionIdIn(List.of(id)).isEmpty());
  }

  @Test void transientReportDownloadFailureKeepsRetryingAndEventuallyIndexes() {
    long id = Long.parseLong(service.upload(actor(student), file("a.docx", new byte[]{1})).id());
    Instant now = Instant.now();
    var claim = tasks.claim(now).orElseThrow();
    assertTrue(tasks.succeed(claim, 2, now.plusSeconds(1)));
    storage.data.put(claim.attemptPrefix() + "report.json", reportWithFailures(2));
    storage.failDownload = true;
    var backfill = new RuleIssueBackfill(submissions, ruleIssues, storage, transactions);
    for (long second : new long[]{2, 63, 184, 425}) backfill.processBatch(now.plusSeconds(second));
    var waiting = submissions.findById(id).orElseThrow();
    assertEquals(RuleIndexStatus.PENDING, waiting.getRuleIndexStatus());
    assertEquals(4, waiting.getRuleIndexAttempts());
    assertTrue(ruleIssues.findBySubmissionIdIn(List.of(id)).isEmpty());
    storage.failDownload = false;
    backfill.processBatch(now.plusSeconds(906));
    assertEquals(RuleIndexStatus.READY, submissions.findById(id).orElseThrow().getRuleIndexStatus());
    assertEquals(2, ruleIssues.findBySubmissionIdIn(List.of(id)).get(0).getIssueCount());
  }

  @Test void malformedHistoricalReportIsUnavailableWithoutReplacingProblemCount() {
    long id = Long.parseLong(service.upload(actor(student), file("a.docx", new byte[]{1})).id());
    Instant now = Instant.now();
    var claim = tasks.claim(now).orElseThrow();
    assertTrue(tasks.succeed(claim, 2, now.plusSeconds(1)));
    storage.data.put(claim.attemptPrefix() + "report.json",
        "not a report".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var backfill = new RuleIssueBackfill(submissions, ruleIssues, storage, transactions);
    backfill.processBatch(now.plusSeconds(2));
    var unresolved = submissions.findById(id).orElseThrow();
    assertEquals(RuleIndexStatus.UNAVAILABLE, unresolved.getRuleIndexStatus());
    assertEquals(2, unresolved.getProblemCount());
    assertTrue(ruleIssues.findBySubmissionIdIn(List.of(id)).isEmpty());
  }

  @Test void retriesServicesOnlyThreeTimesAndRejectsAllFailedDownloads() {
    long id = Long.parseLong(service.upload(actor(student), file("a.docx", new byte[]{1})).id());
    Instant now = Instant.now();
    for (int i = 1; i <= 3; i++) {
      var claim = tasks.claim(now.plusSeconds(i * 61)).orElseThrow();
      assertEquals(i, claim.attempt());
      assertTrue(tasks.fail(claim, true, "审核服务异常，请重新提交或联系管理员", now.plusSeconds(i * 61)));
    }
    assertTrue(tasks.claim(now.plusSeconds(1000)).isEmpty());
    var view = service.detail(actor(student), id);
    assertEquals(SubmissionStatus.FAILED, view.status()); assertNull(view.problemCount());
    assertFalse(view.canDownloadOriginal()); assertFalse(view.canDownloadMarked());
    assertThrows(ApiException.class, () -> service.prepareDownload(actor(student), id, "original"));
  }

  @Test void transientFailureReasonSurvivesRetryClaimUpdatesOnLaterFailureAndClearsOnSuccess() {
    long id = Long.parseLong(service.upload(actor(student), file("transient.docx", new byte[]{1})).id());
    Instant now = Instant.now();

    var first = tasks.claim(now).orElseThrow();
    assertTrue(tasks.fail(first, true, "审核服务暂时不可用", now.plusSeconds(1)));
    assertEquals("审核服务暂时不可用", submissions.findById(id).orElseThrow().getErrorMessage());

    var second = tasks.claim(now.plusSeconds(901)).orElseThrow();
    assertEquals("审核服务暂时不可用", submissions.findById(id).orElseThrow().getErrorMessage());
    assertTrue(tasks.fail(second, true, "审核引擎连接超时", now.plusSeconds(902)));
    assertEquals("审核引擎连接超时", submissions.findById(id).orElseThrow().getErrorMessage());

    var third = tasks.claim(now.plusSeconds(1803)).orElseThrow();
    assertEquals("审核引擎连接超时", submissions.findById(id).orElseThrow().getErrorMessage());
    assertTrue(tasks.succeed(third, 2, now.plusSeconds(1804)));
    assertNull(submissions.findById(id).orElseThrow().getErrorMessage());
  }

  @Test void detailViewReturnsFinalFailureReasonAfterRetry() {
    long id = Long.parseLong(service.upload(actor(student), file("retry.docx", new byte[]{1})).id());
    Instant now = Instant.now();
    var first = tasks.claim(now).orElseThrow();
    assertTrue(tasks.fail(first, true, "temporary audit outage", now.plusSeconds(1)));
    var second = tasks.claim(now.plusSeconds(901)).orElseThrow();
    assertTrue(tasks.fail(second, false, "annotation validation failed", now.plusSeconds(902)));

    var failed = service.detail(actor(student), id);
    assertEquals(SubmissionStatus.FAILED, failed.status());
    assertEquals("annotation validation failed", failed.errorMessage());
  }

  @Test void blankFailureMessagesPreserveKnownReasonAndUseGenericWhenNoneExists() {
    Instant now = Instant.now().plusSeconds(10_000);

    long blankId = Long.parseLong(service.upload(actor(student), file("blank.docx", new byte[]{1})).id());
    var blankFirst = tasks.claim(now).orElseThrow();
    assertTrue(tasks.fail(blankFirst, true, "已知失败原因", now.plusSeconds(1)));
    var blankSecond = tasks.claim(now.plusSeconds(31)).orElseThrow();
    assertTrue(tasks.fail(blankSecond, false, "   ", now.plusSeconds(32)));
    assertEquals("已知失败原因", service.detail(actor(student), blankId).errorMessage());

    long nullId = Long.parseLong(service.upload(actor(student), file("null.docx", new byte[]{1})).id());
    var nullFirst = tasks.claim(now.plusSeconds(40)).orElseThrow();
    assertTrue(tasks.fail(nullFirst, true, "另一条已知原因", now.plusSeconds(41)));
    var nullSecond = tasks.claim(now.plusSeconds(71)).orElseThrow();
    assertTrue(tasks.fail(nullSecond, false, null, now.plusSeconds(72)));
    assertEquals("另一条已知原因", service.detail(actor(student), nullId).errorMessage());

    long unknownId = Long.parseLong(service.upload(actor(student), file("unknown-reason.docx", new byte[]{1})).id());
    var unknown = tasks.claim(now.plusSeconds(80)).orElseThrow();
    assertTrue(tasks.fail(unknown, false, null, now.plusSeconds(81)));
    assertEquals("审核服务异常，请重新提交或联系管理员",
        service.detail(actor(student), unknownId).errorMessage());
  }

  @Test void exhaustedLeasesRetainLastKnownReasonAndUseGenericMessageWithoutOne() {
    long knownId = Long.parseLong(service.upload(actor(student), file("known.docx", new byte[]{1})).id());
    Instant now = Instant.now();
    var first = tasks.claim(now).orElseThrow();
    assertTrue(tasks.fail(first, true, "审核引擎连接超时", now.plusSeconds(1)));
    tasks.claim(now.plusSeconds(901)).orElseThrow();
    tasks.claim(now.plusSeconds(1802)).orElseThrow();
    assertEquals("审核引擎连接超时", submissions.findById(knownId).orElseThrow().getErrorMessage());
    assertEquals(1, tasks.recoverExhausted(now.plusSeconds(2703)));
    assertEquals("审核引擎连接超时", submissions.findById(knownId).orElseThrow().getErrorMessage());

    long unknownId = Long.parseLong(service.upload(actor(student), file("unknown.docx", new byte[]{1})).id());
    Instant unknownStart = now.plusSeconds(3000);
    tasks.claim(unknownStart).orElseThrow();
    tasks.claim(unknownStart.plusSeconds(901)).orElseThrow();
    tasks.claim(unknownStart.plusSeconds(1802)).orElseThrow();
    assertNull(submissions.findById(unknownId).orElseThrow().getErrorMessage());
    assertEquals(1, tasks.recoverExhausted(unknownStart.plusSeconds(2703)));
    assertEquals("审核服务异常，请重新提交或联系管理员",
        submissions.findById(unknownId).orElseThrow().getErrorMessage());
  }

  @Test void damagedDocumentIsPermanentFailureAndExpiredFinalLeaseIsRecovered() {
    service.upload(actor(student), file("broken.docx", new byte[]{1}));
    Instant now = Instant.now();
    var claim = tasks.claim(now).orElseThrow();
    tasks.fail(claim, false, "文档损坏或无法解析，请重新保存为 DOCX 后上传", now);
    assertTrue(tasks.claim(now.plusSeconds(1000)).isEmpty());
    service.upload(actor(student), file("other.docx", new byte[]{2}));
    tasks.claim(now.plusSeconds(1100)).orElseThrow();
    tasks.claim(now.plusSeconds(2100)).orElseThrow();
    tasks.claim(now.plusSeconds(3100)).orElseThrow();
    tasks.recoverExhausted(now.plusSeconds(4100));
    assertEquals(2, submissions.countByStatus(SubmissionStatus.FAILED));
  }

  @Test void teacherStatisticsUseOnlyLatestSuccessfulSubmission() {
    Instant now = Instant.now();
    service.upload(actor(student), file("first.docx", new byte[]{1}));
    tasks.succeed(tasks.claim(now.plusSeconds(1)).orElseThrow(), 8, now.plusSeconds(2));
    service.upload(actor(student), file("second.docx", new byte[]{1}));
    tasks.succeed(tasks.claim(now.plusSeconds(3)).orElseThrow(), 3, now.plusSeconds(4));
    service.upload(actor(student), file("failed.docx", new byte[]{1}));
    tasks.fail(tasks.claim(now.plusSeconds(5)).orElseThrow(), false, "文档损坏", now.plusSeconds(6));
    var summary = service.students(actor(teacher), "student", 1, 20).items().get(0);
    assertEquals(2, summary.submissionCount()); assertEquals(3, summary.latestProblemCount());
    assertNotNull(summary.lastSubmittedAt());
    var dashboard = service.dashboard(actor(admin));
    assertEquals(2, dashboard.activeStudents()); assertEquals(1, dashboard.activeTeachers());
    assertEquals(3, dashboard.totalSubmissions());
    assertEquals(30, dashboard.dailySubmissions().size());
    assertEquals(4, dashboard.statusDistribution().size());
    assertTrue(dashboard.studentsByCollege().stream().anyMatch(row -> row.collegeCode()==null
        && row.collegeName().equals("待归属") && row.count()==2));
    assertThrows(ApiException.class, () -> service.dashboard(actor(teacher)));
  }

  @Test void successfulWorkerPreservesOriginalAndDownloadsOnlyCurrentMarkedResult() throws Exception {
    var view = service.upload(actor(student), file("论文.docx", new byte[]{1,2}));
    var claim = tasks.claim(Instant.now()).orElseThrow();
    new SubmissionWorker(tasks, submissions, storage, (original, directory) -> {
      try {
        assertArrayEquals(new byte[]{1,2}, Files.readAllBytes(original));
        Path marked = Files.write(directory.resolve("marked.docx"), new byte[]{3,4});
        return new AuditEngine.Result(9, marked, Files.write(directory.resolve("report.docx"), new byte[]{5,6}), reportWithFailures(9));
      } catch (IOException error) { throw new EngineFailure(true,"test read failed",error); }
    }).process(claim);
    var completed=service.detail(actor(student), Long.parseLong(view.id()));
    assertEquals(SubmissionStatus.SUCCESS, completed.status());
    assertEquals(RuleIndexStatus.READY, submissions.findById(claim.id()).orElseThrow().getRuleIndexStatus());
    assertEquals(9, ruleIssues.findBySubmissionIdIn(List.of(claim.id())).get(0).getIssueCount());
    assertTrue(completed.canDownloadReport());
    var download = service.prepareDownload(actor(teacher), claim.id(), "marked");
    assertEquals("论文.marked.docx", download.filename());
    try (var object = service.openDownload(download)) { assertArrayEquals(new byte[]{3,4}, object.stream().readAllBytes()); }
    assertTrue(storage.data.containsKey(claim.directory()+"/original.docx"));
    assertTrue(storage.data.containsKey(claim.attemptPrefix()+"report.json"));
    var report=service.prepareDownload(actor(student),claim.id(),"report");
    try(var object=service.openDownload(report)) { assertArrayEquals(new byte[]{5,6},object.stream().readAllBytes()); }
  }

  @Test void cleanupCannotDeleteCommittedSuccessWhenCompletionAcknowledgementIsLost() throws Exception {
    service.upload(actor(student), file("paper.docx", new byte[]{1}));
    var claim = tasks.claim(Instant.now()).orElseThrow();
    SubmissionTasks lostAcknowledgement = new SubmissionTasks(submissions, ruleIssues, transactions, Duration.ofMinutes(15)) {
      @Override public boolean succeedIndexed(Claim value, int count, RuleIssueSummary summary, Instant now) {
        super.succeedIndexed(value,count,summary,now);
        throw new IllegalStateException("connection lost after commit");
      }
    };
    new SubmissionWorker(lostAcknowledgement, submissions, storage, (original,directory) ->
        new AuditEngine.Result(0,original,original,reportWithFailures(0))).process(claim);
    assertEquals(SubmissionStatus.SUCCESS, submissions.findById(claim.id()).orElseThrow().getStatus());
    assertTrue(storage.data.containsKey(claim.attemptPrefix()+"marked.docx"));
  }

  @Test void failedCleanupIsRetriedFromPersistentRecord() {
    service.upload(actor(student), file("bad.docx", new byte[]{1}));
    var claim = tasks.claim(Instant.now()).orElseThrow();
    storage.failDeletion = true;
    var worker = new SubmissionWorker(tasks,submissions,storage,(original,directory) -> {
      throw new EngineFailure(false,"文档损坏");
    });
    worker.process(claim);
    assertEquals(SubmissionStatus.FAILED,submissions.findById(claim.id()).orElseThrow().getStatus());
    assertFalse(storage.data.isEmpty());
    storage.failDeletion = false;
    worker.compensateCleanup();
    assertTrue(storage.data.isEmpty());
  }

  @Test void twoConcurrentWorkersCannotClaimSameSubmission() throws Exception {
    service.upload(actor(student), file("a.docx", new byte[]{1}));
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Callable<Boolean> work = () -> { start.await(); return tasks.claim(Instant.now()).isPresent(); };
      var first=executor.submit(work); var second=executor.submit(work); start.countDown();
      assertNotEquals(first.get(10,TimeUnit.SECONDS), second.get(10,TimeUnit.SECONDS));
    }
  }

  @Test void storageFailureRetainsFailedRowForPersistentCleanup() {
    storage.failUpload = true; storage.failDeletion = true;
    assertThrows(ApiException.class, () -> service.upload(actor(student),file("a.docx",new byte[]{1})));
    assertEquals(1,submissions.countByStatus(SubmissionStatus.FAILED));
    assertFalse(storage.data.isEmpty());
    storage.failDeletion=false;
    new SubmissionWorker(tasks,submissions,storage,(original,directory)->null).compensateCleanup();
    assertTrue(storage.data.isEmpty());
  }

  @Test void oversizedFilesAndStaleDisabledSessionsAreRejected() {
    var oversized = new MockMultipartFile("file","a.docx","application/octet-stream",new byte[]{1}) {
      @Override public long getSize() { return SubmissionService.MAX_BYTES+1; }
    };
    assertThrows(ApiException.class,()->service.upload(actor(student),oversized));
    var existingSession=actor(student); student.setActive(false); users.save(student);
    assertThrows(ApiException.class,()->service.upload(existingSession,file("a.docx",new byte[]{1})));
    assertEquals(0,submissions.count());
  }

  @Test void proxyUploadWaitsForConcurrentStudentDeactivation() throws Exception {
    assertUploadWaitsForStudentDeactivation(false);
  }

  @Test void directUploadWaitsForConcurrentStudentDeactivation() throws Exception {
    assertUploadWaitsForStudentDeactivation(true);
  }

  private void assertUploadWaitsForStudentDeactivation(boolean direct) throws Exception {
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var uploading = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var deactivate = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
          .execute(status -> {
            User current = users.findByIdForUpdate(student.getId()).orElseThrow();
            current.setActive(false); users.saveAndFlush(current);
            locked.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release timed out"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            return null;
          }));
      assertTrue(locked.await(5, TimeUnit.SECONDS));
      var upload = executor.submit(() -> {
        uploading.countDown();
        if (direct) service.createUpload(actor(student), "a.docx", 1);
        else service.upload(actor(student), file("a.docx", new byte[]{1}));
      });
      assertTrue(uploading.await(5, TimeUnit.SECONDS));
      try { assertThrows(TimeoutException.class, () -> upload.get(1, TimeUnit.SECONDS)); }
      finally { release.countDown(); }
      deactivate.get(5, TimeUnit.SECONDS);
      var failure = assertThrows(ExecutionException.class, () -> upload.get(5, TimeUnit.SECONDS));
      assertEquals("FORBIDDEN", assertInstanceOf(ApiException.class, failure.getCause()).code());
    } finally { release.countDown(); }
    assertEquals(0, submissions.count());
    assertTrue(storage.data.isEmpty());
  }

  @Test void deletedStudentDuringUploadValidationDoesNotCreateSubmission() {
    var existingSession = actor(student);
    var file = new MockMultipartFile("file", "a.docx", "application/octet-stream", new byte[]{1}) {
      @Override public String getOriginalFilename() {
        users.deleteById(student.getId());
        return super.getOriginalFilename();
      }
    };
    var failure = assertThrows(ApiException.class, () -> service.upload(existingSession, file));
    assertEquals("FORBIDDEN", failure.code());
    assertEquals(0, submissions.count());
    assertTrue(storage.data.isEmpty());
  }

  @Test void uploadReservationPreventsPrematureClaimAndDownload() {
    storage.beforeUpload=()->{
      var row=submissions.findAll().get(0);
      assertEquals(SubmissionStatus.PENDING,row.getStatus());
      assertTrue(tasks.claim(Instant.now()).isEmpty());
      assertFalse(service.detail(actor(student),row.getId()).canDownloadOriginal());
      assertThrows(ApiException.class,()->service.prepareDownload(actor(student),row.getId(),"original"));
    };
    var result=service.upload(actor(student),file("a.docx",new byte[]{1}));
    assertTrue(result.canDownloadOriginal());
    assertTrue(tasks.claim(Instant.now()).isPresent());
  }

  @Test void failedQueueReleaseRetainsOriginalAndRecoversAfterReservationExpiry() {
    PlatformTransactionManager failSecondCommit = new PlatformTransactionManager() {
      int commits;
      public TransactionStatus getTransaction(TransactionDefinition definition) {return transactions.getTransaction(definition);}
      public void rollback(TransactionStatus status) {transactions.rollback(status);}
      public void commit(TransactionStatus status) {
        if(++commits==2) {transactions.rollback(status);throw new IllegalStateException("queue release DB commit unavailable");}
        transactions.commit(status);
      }
    };
    var unavailableRelease=new SubmissionService(submissions,users,storage,failSecondCommit,presigner,
        new TransferProperties(TransferProperties.Mode.DIRECT,600));
    assertThrows(IllegalStateException.class,()->unavailableRelease.upload(actor(student),file("a.docx",new byte[]{1})));
    var row=submissions.findAll().get(0);
    assertTrue(storage.data.containsKey(row.getStorageDir()+"/original.docx"));
    assertTrue(tasks.claim(Instant.now()).isEmpty());
    var recovered=tasks.claim(row.getUpdatedAt().plusSeconds(1)).orElseThrow();
    assertEquals(row.getId(),recovered.id()); assertEquals(1,recovered.attempt());
  }

  @Test void processCrashBeforeObjectUploadEventuallyFailsAndCompensates() {
    Instant now=Instant.now();
    var row=new Submission(student.getId(),"interrupted.docx",1,"submissions/"+UUID.randomUUID(),now);
    row.reserveUploadUntil(now.plus(SubmissionService.UPLOAD_RESERVATION));
    submissions.saveAndFlush(row);
    assertTrue(tasks.claim(now).isEmpty());
    var worker=new SubmissionWorker(tasks,submissions,storage,(original,directory)->null);
    Instant afterExpiry=now.plus(SubmissionService.UPLOAD_RESERVATION).plusSeconds(1);
    for(int attempt=0;attempt<3;attempt++) worker.process(tasks.claim(afterExpiry.plusSeconds(attempt*60)).orElseThrow());
    assertEquals(SubmissionStatus.FAILED,submissions.findById(row.getId()).orElseThrow().getStatus());
    assertTrue(storage.data.isEmpty());
  }

  @Test void staleAttemptCannotRemoveNewerSuccessfulArtifact() throws Exception {
    service.upload(actor(student),file("a.docx",new byte[]{1}));
    Instant now=Instant.now(); var first=tasks.claim(now).orElseThrow();
    var second=tasks.claim(now.plusSeconds(901)).orElseThrow();
    storage.data.put(second.attemptPrefix()+"marked.docx",new byte[]{9});
    tasks.succeed(second,0,now.plusSeconds(902));
    new SubmissionWorker(tasks,submissions,storage,(original,directory)->new AuditEngine.Result(0,original,original,reportWithFailures(0))).process(first);
    assertArrayEquals(new byte[]{9},storage.data.get(second.attemptPrefix()+"marked.docx"));
    assertFalse(storage.data.containsKey(first.attemptPrefix()+"marked.docx"));
  }
  @Test void controllerAuthorizesImmediatelyButOpensStorageOnlyWhenStreamingStarts() throws Exception {
    long id=Long.parseLong(service.upload(actor(student),file("a.docx",new byte[]{1,2})).id());
    var controller=new SubmissionController(service);
    assertThrows(ApiException.class,()->controller.download(actor(other),id,"original"));
    var response=controller.download(actor(student),id,"original");
    assertEquals(0,storage.reads);
    var output=new ByteArrayOutputStream();response.getBody().writeTo(output);
    assertEquals(1,storage.reads);assertArrayEquals(new byte[]{1,2},output.toByteArray());
  }
  @Test void queuedDownloadRechecksDisablePasswordAndTeacherAssignment() {
    long id=Long.parseLong(service.upload(actor(student),file("a.docx",new byte[]{1})).id());
    var teacherPlan=service.prepareDownload(actor(teacher),id,"original");
    var studentPlan=service.prepareDownload(actor(student),id,"original");
    student.setActive(false);users.save(student);
    assertThrows(ApiException.class,()->service.openDownload(studentPlan));
    student.setActive(true);student.changePassword("new-hash",false);users.save(student);
    assertEquals("SESSION_EXPIRED",assertThrows(ApiException.class,()->service.openDownload(studentPlan)).code());
    student.updateProfile("学生","计算机",null);users.save(student);
    assertThrows(ApiException.class,()->service.openDownload(teacherPlan));
    assertEquals(0,storage.reads);
  }

  @Test void directUploadCompletesOnlyAfterObjectMetadataMatches() {
    var ticket=service.createUpload(actor(student),"论文.docx",4);
    long id=Long.parseLong(ticket.submissionId());
    assertTrue(ticket.url().startsWith("https://cos.test/submissions/"));
    assertTrue(storage.data.isEmpty());
    var row=submissions.findById(id).orElseThrow();
    assertTrue(row.isUploadReserved(Instant.now()));
    storage.data.put(row.getStorageDir()+"/original.docx",new byte[]{1,2,3,4});
    var view=service.completeUpload(actor(student),id);
    assertFalse(view.uploadReserved()); assertTrue(view.canDownloadOriginal());
    assertTrue(tasks.claim(Instant.now().plusSeconds(1)).isPresent());
    assertFalse(service.completeUpload(actor(student),id).uploadReserved());
  }

  @Test void directUploadRejectsMissingAndMismatchedObjects() {
    long missing=Long.parseLong(service.createUpload(actor(student),"missing.docx",4).submissionId());
    var missingError=assertThrows(ApiException.class,()->service.completeUpload(actor(student),missing));
    assertEquals("UPLOAD_NOT_FOUND",missingError.code());
    assertTrue(submissions.findById(missing).orElseThrow().isUploadReserved(Instant.now()));

    long wrongSize=Long.parseLong(service.createUpload(actor(student),"size.docx",4).submissionId());
    var sizeRow=submissions.findById(wrongSize).orElseThrow();
    storage.data.put(sizeRow.getStorageDir()+"/original.docx",new byte[]{1,2,3});
    var sizeError=assertThrows(ApiException.class,()->service.completeUpload(actor(student),wrongSize));
    assertEquals("UPLOAD_SIZE_MISMATCH",sizeError.code());
    assertEquals(SubmissionStatus.FAILED,submissions.findById(wrongSize).orElseThrow().getStatus());
    assertFalse(storage.data.containsKey(sizeRow.getStorageDir()+"/original.docx"));

    storage.contentType="text/plain";
    long wrongType=Long.parseLong(service.createUpload(actor(student),"type.docx",4).submissionId());
    var typeRow=submissions.findById(wrongType).orElseThrow();
    storage.data.put(typeRow.getStorageDir()+"/original.docx",new byte[]{1,2,3,4});
    var typeError=assertThrows(ApiException.class,()->service.completeUpload(actor(student),wrongType));
    assertEquals("UPLOAD_TYPE_MISMATCH",typeError.code());
    assertEquals(SubmissionStatus.FAILED,submissions.findById(wrongType).orElseThrow().getStatus());
  }

  @Test void directDownloadUrlReusesAuthorizationAndOnlyMarksSuccess() {
    long id=Long.parseLong(service.createUpload(actor(student),"论文.docx",4).submissionId());
    var row=submissions.findById(id).orElseThrow();
    storage.data.put(row.getStorageDir()+"/original.docx",new byte[]{1,2,3,4});
    service.completeUpload(actor(student),id);
    var studentUrl=service.prepareDownloadUrl(actor(student),id,"original");
    assertEquals("论文.docx",studentUrl.filename());
    assertTrue(studentUrl.url().startsWith("https://cos.test/"));
    assertEquals(studentUrl.url(),service.prepareDownloadUrl(actor(teacher),id,"original").url());
    assertThrows(ApiException.class,()->service.prepareDownloadUrl(actor(other),id,"original"));
    assertThrows(ApiException.class,()->service.prepareDownloadUrl(actor(admin),id,"original"));
    assertThrows(ApiException.class,()->service.prepareDownloadUrl(actor(student),id,"marked"));
  }

  @Test void directModeCanBeDisabledAndRefreshKeepsSameKey() {
    var ticket=service.createUpload(actor(student),"a.docx",4);
    var refreshed=service.refreshUpload(actor(student),Long.parseLong(ticket.submissionId()));
    assertEquals(ticket.submissionId(),refreshed.submissionId());
    assertEquals(ticket.url(),refreshed.url());
    var proxy=new SubmissionService(submissions,users,storage,transactions,presigner,
        new TransferProperties(TransferProperties.Mode.PROXY,600));
    assertEquals("proxy",proxy.transferMode(actor(student)));
    var disabled=assertThrows(ApiException.class,()->proxy.createUpload(actor(student),"a.docx",4));
    assertEquals("DIRECT_TRANSFER_DISABLED",disabled.code());
  }

  @Test void successfulReportUsesItsOwnObjectInProxyAndDirectDownloads() throws Exception {
    long id=Long.parseLong(service.upload(actor(student),file("论文.docx",new byte[]{1})).id());
    var claim=tasks.claim(Instant.now()).orElseThrow();
    storage.data.put(claim.attemptPrefix()+"marked.docx",new byte[]{2});
    storage.data.put(claim.attemptPrefix()+"report.docx",new byte[]{3,4});
    assertTrue(tasks.succeed(claim,7,Instant.now()));
    assertTrue(service.detail(actor(student),id).canDownloadReport());
    for (User user:List.of(student,teacher)) {
      var plan=service.prepareDownload(actor(user),id,"report");
      assertEquals("论文.report.docx",plan.filename());
      try(var object=service.openDownload(plan)) {
        assertArrayEquals(new byte[]{3,4},object.stream().readAllBytes());
      }
      var ticket=service.prepareDownloadUrl(actor(user),id,"report");
      assertEquals("https://cos.test/"+claim.attemptPrefix()+"report.docx",ticket.url());
      assertEquals("论文.report.docx",ticket.filename());
    }
    for(User user:List.of(other,admin)) {
      assertEquals("FORBIDDEN",assertThrows(ApiException.class,
          ()->service.prepareDownload(actor(user),id,"report")).code());
      assertEquals("FORBIDDEN",assertThrows(ApiException.class,
          ()->service.prepareDownloadUrl(actor(user),id,"report")).code());
    }
    assertEquals("INVALID_FILE_KIND",assertThrows(ApiException.class,
        ()->service.prepareDownload(actor(student),id,"anything")).code());
  }

  @Test void unfinishedAndFailedSubmissionsCannotDownloadReportsThroughEitherRoute() {
    long id=Long.parseLong(service.upload(actor(student),file("论文.docx",new byte[]{1})).id());
    assertReportUnavailable(id);
    var claim=tasks.claim(Instant.now()).orElseThrow();
    assertReportUnavailable(id);
    assertTrue(tasks.fail(claim,false,"审核失败",Instant.now()));
    assertReportUnavailable(id);
  }

  private void assertReportUnavailable(long id) {
    assertFalse(service.detail(actor(student),id).canDownloadReport());
    assertEquals("FILE_NOT_AVAILABLE",assertThrows(ApiException.class,
        ()->service.prepareDownload(actor(student),id,"report")).code());
    assertEquals("FILE_NOT_AVAILABLE",assertThrows(ApiException.class,
        ()->service.prepareDownloadUrl(actor(student),id,"report")).code());
  }

  @Test void historicalSuccessKeepsOriginalAndMarkedButHasNoReport() {
    long id=Long.parseLong(service.upload(actor(student),file("历史论文.docx",new byte[]{1})).id());
    var claim=tasks.claim(Instant.now()).orElseThrow();
    assertTrue(tasks.succeed(claim,4,Instant.now()));
    new org.springframework.jdbc.core.JdbcTemplate(dataSource)
        .update("update submission set report_available=false where id=?",id);
    var view=service.detail(actor(student),id);
    assertEquals(SubmissionStatus.SUCCESS,view.status());
    assertTrue(view.canDownloadOriginal()); assertTrue(view.canDownloadMarked());
    assertReportUnavailable(id);
    assertEquals("历史论文.marked.docx",service.prepareDownload(actor(student),id,"marked").filename());
  }

  @Test void reportOrJsonUploadFailureCannotPublishPartialResult() {
    for(String artifact:List.of("report.docx","report.json")) {
      long id=Long.parseLong(service.upload(actor(student),file("论文.docx",new byte[]{1})).id());
      var claim=tasks.claim(Instant.now()).orElseThrow();
      storage.failKey=claim.attemptPrefix()+artifact;
      new SubmissionWorker(tasks,submissions,storage,
          (original,directory)->new AuditEngine.Result(0,original,original,reportWithFailures(0))).process(claim);
      assertEquals(SubmissionStatus.PENDING,service.detail(actor(student),id).status());
      assertReportUnavailable(id);
      assertTrue(storage.data.containsKey(claim.directory()+"/original.docx"));
      assertTrue(storage.data.keySet().stream().noneMatch(key->key.startsWith(claim.attemptPrefix())));
      storage.failKey=null;
    }
  }

  @Test void reportStreamingRechecksPermissionsAndUsesCurrentAttempt() throws Exception {
    long id=Long.parseLong(service.upload(actor(student),file("论文.docx",new byte[]{1})).id());
    Instant now=Instant.now();
    var first=tasks.claim(now).orElseThrow();
    var second=tasks.claim(now.plusSeconds(901)).orElseThrow();
    storage.data.put(first.attemptPrefix()+"report.docx",new byte[]{2});
    storage.data.put(second.attemptPrefix()+"report.docx",new byte[]{3});
    assertTrue(tasks.succeed(second,0,now.plusSeconds(902)));
    assertFalse(tasks.succeed(first,0,now.plusSeconds(903)));
    var plan=service.prepareDownload(actor(teacher),id,"report");
    try(var object=service.openDownload(plan)) {assertArrayEquals(new byte[]{3},object.stream().readAllBytes());}
    var controller=new SubmissionController(service);
    var response=controller.download(actor(teacher),id,"report");
    assertEquals(SubmissionService.DOCX,response.getHeaders().getContentType().toString());
    assertEquals("论文.report.docx",response.getHeaders().getContentDisposition().getFilename());
    student.updateProfile("学生","计算机",null);users.save(student);
    int reads=storage.reads;
    assertThrows(ApiException.class,()->response.getBody().writeTo(new ByteArrayOutputStream()));
    assertEquals(reads,storage.reads);
  }

  private static CurrentUser actor(User user) {
    return new CurrentUser(user.getId(), user.getUsername(), user.getRole(), user.getName(), user.isMustChangePassword());
  }
  private static byte[] reportWithFailures(int count) {
    String failure = "{\"ruleId\":\"BODY-LINE\",\"judgement\":\"FAIL\"}";
    String body = "{\"report\":{\"results\":[" + String.join(",", Collections.nCopies(count, failure))
        + "],\"ruleCoverage\":[{\"ruleId\":\"BODY-LINE\",\"ruleDescription\":\"正文行距应为1.5倍\"}]}}";
    return body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }
  private static MockMultipartFile file(String name, byte[] bytes) { return new MockMultipartFile("file", name, "application/octet-stream", bytes); }
  static class MemoryBoundary implements ObjectStorage {
    final Map<String, byte[]> data = new HashMap<>();
    boolean failUpload, failDeletion, failDownload;
    String failKey;
    int reads;
    String contentType=SubmissionService.DOCX;
    Runnable beforeUpload=()->{};
    public void put(String key, InputStream stream, long size, String type) throws IOException {
      beforeUpload.run();
      data.put(key, stream.readAllBytes());
      if(failUpload || key.equals(failKey)) throw new IOException("object accepted but response lost");
    }
    public StoredObject get(String key) throws IOException {
      reads++;
      if (failDownload) throw new IOException("temporary storage failure");
      byte[] bytes = data.get(key);
      if(bytes==null) throw new FileNotFoundException("test object does not exist");
      return new StoredObject(new ByteArrayInputStream(bytes), bytes.length);
    }
    public Optional<StoredMetadata> head(String key) {
      byte[] bytes=data.get(key);
      if(bytes==null) return Optional.empty();
      return Optional.of(new StoredMetadata(bytes.length,contentType,"test-etag"));
    }
    public void deletePrefix(String prefix) throws IOException {
      if(failDeletion) throw new IOException("storage unavailable");
      data.keySet().removeIf(key -> key.startsWith(prefix));
    }
  }

  static class FakePresigner implements ObjectPresigner {
    public UploadTicket uploadUrl(String key,long size,String contentType,Instant expiresAt) {
      return new UploadTicket(url(key),expiresAt);
    }
    public DownloadTicket downloadUrl(String key,String filename,Instant expiresAt) {
      return new DownloadTicket(url(key),filename,expiresAt);
    }
    private static java.net.URL url(String key) {
      try { return URI.create("https://cos.test/"+key).toURL(); }
      catch(java.net.MalformedURLException error) { throw new IllegalStateException(error); }
    }
  }
}
