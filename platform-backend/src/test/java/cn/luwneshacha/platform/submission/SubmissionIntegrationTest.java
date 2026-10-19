package cn.luwneshacha.platform.submission;

import static org.junit.jupiter.api.Assertions.*;
import cn.luwneshacha.platform.account.*;
import cn.luwneshacha.platform.auth.CurrentUser;
import cn.luwneshacha.platform.common.ApiException;
import cn.luwneshacha.platform.storage.ObjectStorage;
import cn.luwneshacha.platform.engine.*;
import java.io.*;
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
  @Autowired UserRepository users;
  @Autowired PlatformTransactionManager transactions;
  final MemoryBoundary storage = new MemoryBoundary();
  SubmissionService service;
  SubmissionTasks tasks;
  User student, other, teacher, admin;

  @BeforeEach void setup() {
    submissions.deleteAllInBatch(); users.deleteAllInBatch(); storage.data.clear();
    teacher = users.save(new User("teacher", "hash", Role.TEACHER, "教师", null, null, false));
    student = users.save(new User("student", "hash", Role.STUDENT, "学生", "计算机", teacher.getId(), false));
    other = users.save(new User("other", "hash", Role.STUDENT, "其他", null, null, false));
    admin = users.save(new User("admin", "hash", Role.ADMIN, "管理员", null, null, false));
    service = new SubmissionService(submissions, users, storage, transactions);
    tasks = new SubmissionTasks(submissions, transactions, Duration.ofMinutes(15));
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
    assertThrows(ApiException.class, () -> service.dashboard(actor(teacher)));
  }

  @Test void successfulWorkerPreservesOriginalAndDownloadsOnlyCurrentMarkedResult() throws Exception {
    var view = service.upload(actor(student), file("论文.docx", new byte[]{1,2}));
    var claim = tasks.claim(Instant.now()).orElseThrow();
    new SubmissionWorker(tasks, submissions, storage, (original, directory) -> {
      try {
        assertArrayEquals(new byte[]{1,2}, Files.readAllBytes(original));
        Path marked = Files.write(directory.resolve("marked.docx"), new byte[]{3,4});
        return new AuditEngine.Result(9, marked, "{}".getBytes());
      } catch (IOException error) { throw new EngineFailure(true,"test read failed",error); }
    }).process(claim);
    assertEquals(SubmissionStatus.SUCCESS, service.detail(actor(student), Long.parseLong(view.id())).status());
    var download = service.prepareDownload(actor(teacher), claim.id(), "marked");
    assertEquals("论文.marked.docx", download.filename());
    try (var object = service.openDownload(download)) { assertArrayEquals(new byte[]{3,4}, object.stream().readAllBytes()); }
    assertTrue(storage.data.containsKey(claim.directory()+"/original.docx"));
    assertTrue(storage.data.containsKey(claim.attemptPrefix()+"report.json"));
  }

  @Test void cleanupCannotDeleteCommittedSuccessWhenCompletionAcknowledgementIsLost() throws Exception {
    service.upload(actor(student), file("paper.docx", new byte[]{1}));
    var claim = tasks.claim(Instant.now()).orElseThrow();
    SubmissionTasks lostAcknowledgement = new SubmissionTasks(submissions, transactions, Duration.ofMinutes(15)) {
      @Override public boolean succeed(Claim value, int count, Instant now) {
        super.succeed(value,count,now);
        throw new IllegalStateException("connection lost after commit");
      }
    };
    new SubmissionWorker(lostAcknowledgement, submissions, storage, (original,directory) ->
        new AuditEngine.Result(0,original,"{}".getBytes())).process(claim);
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
    var unavailableRelease=new SubmissionService(submissions,users,storage,failSecondCommit);
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
    new SubmissionWorker(tasks,submissions,storage,(original,directory)->new AuditEngine.Result(0,original,new byte[]{1})).process(first);
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

  private static CurrentUser actor(User user) {
    return new CurrentUser(user.getId(), user.getUsername(), user.getRole(), user.getName(), user.isMustChangePassword());
  }
  private static MockMultipartFile file(String name, byte[] bytes) { return new MockMultipartFile("file", name, "application/octet-stream", bytes); }
  static class MemoryBoundary implements ObjectStorage {
    final Map<String, byte[]> data = new HashMap<>();
    boolean failUpload, failDeletion;
    int reads;
    Runnable beforeUpload=()->{};
    public void put(String key, InputStream stream, long size, String type) throws IOException {
      beforeUpload.run();
      data.put(key, stream.readAllBytes());
      if(failUpload) throw new IOException("object accepted but response lost");
    }
    public StoredObject get(String key) throws IOException {
      reads++;
      byte[] bytes = data.get(key);
      if(bytes==null) throw new FileNotFoundException("test object does not exist");
      return new StoredObject(new ByteArrayInputStream(bytes), bytes.length);
    }
    public void deletePrefix(String prefix) throws IOException {
      if(failDeletion) throw new IOException("storage unavailable");
      data.keySet().removeIf(key -> key.startsWith(prefix));
    }
  }
}
