package cn.luwneshacha.platform.account;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.luwneshacha.platform.auth.CurrentUser;
import cn.luwneshacha.platform.auth.AuthService;
import cn.luwneshacha.platform.auth.PasswordHasher;
import cn.luwneshacha.platform.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Requires real PostgreSQL: persistence, rollback and row locks are not simulated. */
@Testcontainers
@SpringBootTest(classes = MajorRenameIntegrationTest.TestApplication.class,
    properties = "platform.bootstrap.enabled=false")
@AutoConfigureMockMvc
class MajorRenameIntegrationTest {
  private static final String COLLEGE = College.INFORMATION_ENGINEERING.name();
  @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
  @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
    properties.add("spring.datasource.username", DATABASE::getUsername);
    properties.add("spring.datasource.password", DATABASE::getPassword);
  }
  @SpringBootConfiguration @EnableAutoConfiguration
  @ComponentScan(basePackages = {"cn.luwneshacha.platform.account", "cn.luwneshacha.platform.auth", "cn.luwneshacha.platform.common"},
      excludeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = SpringBootConfiguration.class))
  @EntityScan({"cn.luwneshacha.platform.account", "cn.luwneshacha.platform.submission"})
  @EnableJpaRepositories({"cn.luwneshacha.platform.account", "cn.luwneshacha.platform.submission"})
  static class TestApplication {}

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired UserRepository users;
  @Autowired UserService accounts;
  @Autowired MajorCatalogRepository majors;
  @Autowired MajorService service;
  @Autowired AuthService auth;
  @Autowired PasswordHasher passwords;
  @Autowired JdbcTemplate database;
  @Autowired PlatformTransactionManager transactions;
  private User school, administrator;
  private MajorCatalog original;

  @BeforeEach void seed() {
    users.findAll().stream().filter(user -> user.getRole() == Role.STUDENT).forEach(users::delete);
    users.deleteAll();
    majors.deleteAll();
    school = users.saveAndFlush(new User("schooladmin", "hash", Role.ADMIN, "校管", null, null, null, false));
    administrator = users.saveAndFlush(new User("collegeadmin", "hash", Role.ADMIN, "院管", null, null, COLLEGE, false));
    original = majors.saveAndFlush(new MajorCatalog(COLLEGE, "原专业"));
  }

  @Test void persistsDirectoryRenameAndUpdatesMatchingStudentAndTeacherProfiles() throws Exception {
    User student = users.saveAndFlush(new User("student01", "hash", Role.STUDENT, "学生", "原专业", null, COLLEGE, false, 2023));
    User teacher = users.saveAndFlush(new User("teacher01", "hash", Role.TEACHER, "教师", "原专业", null, COLLEGE, false));
    User other = users.saveAndFlush(new User("student02", "hash", Role.STUDENT, "其他学院学生", "原专业", null,
        College.EDUCATION_ARTS.name(), false, 2023));
    User inactiveStudent = profile("inactivestudent", Role.STUDENT, COLLEGE, "原专业", false);
    User inactiveTeacher = profile("inactiveteacher", Role.TEACHER, COLLEGE, "原专业", false);
    User historical = profile("historical", Role.STUDENT, COLLEGE, "历史任意专业", true);
    User otherMajor = profile("othermajor", Role.TEACHER, COLLEGE, "原专业扩展", true);
    User otherTeacher = profile("otherteacher", Role.TEACHER, College.EDUCATION_ARTS.name(), "原专业", true);
    User unassigned = profile("unassigned", Role.STUDENT, null, "原专业", true);
    User administratorWithHistoricalMajor = profile("historicaladmin", Role.ADMIN, COLLEGE, "原专业", true);
    List<User> included = List.of(student, teacher, inactiveStudent, inactiveTeacher);
    List<User> excluded = List.of(other, historical, otherMajor, otherTeacher, unassigned, administratorWithHistoricalMajor);
    database.update("update sys_user set updated_at='2020-01-01T00:00:00Z'");
    var snapshots = users.findAll().stream().collect(java.util.stream.Collectors.toMap(User::getId, user -> user));
    service.setActive(CurrentUser.from(school), original.getId(), false);
    rename(administrator, "  新专业  ").andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(original.getId().toString()))
        .andExpect(jsonPath("$.data.active").value(false));
    MajorCatalog saved = majors.findById(original.getId()).orElseThrow();
    assertEquals("新专业", saved.getName());
    assertEquals(COLLEGE, saved.getCollegeCode());
    assertFalse(saved.isActive());
    for (User user : included) {
      User updated = users.findById(user.getId()).orElseThrow();
      assertEquals("新专业", updated.getMajor());
      assertTrue(updated.getUpdatedAt().isAfter(snapshots.get(user.getId()).getUpdatedAt()));
      assertUnrelatedFields(snapshots.get(user.getId()), updated);
    }
    for (User user : excluded) {
      User unchanged = users.findById(user.getId()).orElseThrow();
      assertEquals(user.getMajor(), unchanged.getMajor());
      assertEquals(snapshots.get(user.getId()).getUpdatedAt(), unchanged.getUpdatedAt());
      assertUnrelatedFields(snapshots.get(user.getId()), unchanged);
    }
    var error = assertThrows(ApiException.class, () -> accounts.create(CurrentUser.from(school),
        new UserService.CreateUser("newteacher", "新教师", Role.TEACHER, "原专业", null, COLLEGE, null)));
    assertEquals("INVALID_MAJOR", error.code());
    assertEquals("新专业", accounts.update(CurrentUser.from(school), student.getId(),
        new UserService.UpdateUser("学生改名", "新专业", null, COLLEGE, 2023)).major());
    assertEquals("历史任意专业", accounts.update(CurrentUser.from(school), historical.getId(),
        new UserService.UpdateUser("历史学生改名", "历史任意专业", null, COLLEGE, 2023)).major());
  }

  @Test void invalidAndDuplicateNamesDoNotChangeStoredDirectory() throws Exception {
    User student = profile("unchangedstudent", Role.STUDENT, COLLEGE, "原专业", true);
    MajorCatalog duplicate = new MajorCatalog(COLLEGE, "已有专业");
    duplicate.setActive(false);
    majors.saveAndFlush(duplicate);
    rename(administrator, "已有专业").andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("MAJOR_EXISTS"));
    rename(administrator, "   ").andExpect(status().isBadRequest());
    rename(administrator, "专".repeat(65)).andExpect(status().isBadRequest());
    assertEquals("原专业", majors.findById(original.getId()).orElseThrow().getName());
    assertTrue(majors.findById(original.getId()).orElseThrow().isActive());
    User unchanged = users.findById(student.getId()).orElseThrow();
    assertEquals("原专业", unchanged.getMajor());
    assertEquals(student.getUpdatedAt(), unchanged.getUpdatedAt());
  }

  @Test void enclosingTransactionFailureRollsBackRename() {
    User student = profile("rollbackstudent", Role.STUDENT, COLLEGE, "原专业", true);
    assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
      checkedRename(school, "应当回滚");
      throw new IllegalStateException("Force rollback after the real directory update");
    }));
    assertEquals("原专业", majors.findById(original.getId()).orElseThrow().getName());
    assertEquals("原专业", users.findById(student.getId()).orElseThrow().getMajor());
  }

  @Test void bulkUpdateFailureRollsBackAlreadyFlushedDirectoryAndProfiles() {
    User student = profile("failingstudent", Role.STUDENT, COLLEGE, "原专业", true);
    database.execute("""
        create function reject_test_major_sync() returns trigger language plpgsql as $$
        begin
          if NEW.major = '拒绝同步' then raise exception 'test major synchronization failure'; end if;
          return NEW;
        end $$
        """);
    database.execute("create trigger reject_test_major_sync before update on sys_user for each row execute function reject_test_major_sync()");
    try {
      assertThrows(org.springframework.dao.DataAccessException.class,
          () -> service.rename(CurrentUser.from(administrator), original.getId(), new MajorService.RenameMajor("拒绝同步")));
    } finally {
      database.execute("drop trigger reject_test_major_sync on sys_user");
      database.execute("drop function reject_test_major_sync()");
    }
    assertEquals("原专业", majors.findById(original.getId()).orElseThrow().getName());
    assertEquals("原专业", users.findById(student.getId()).orElseThrow().getMajor());
  }

  @Test void bulkSyncClearsPreviouslyManagedProfilesAndUnchangedNamePreservesTimestamps() {
    User student = profile("managedstudent", Role.STUDENT, COLLEGE, "原专业", true);
    Instant initial = users.findById(student.getId()).orElseThrow().getUpdatedAt();
    service.rename(CurrentUser.from(administrator), original.getId(), new MajorService.RenameMajor(" 原专业 "));
    assertEquals(initial, users.findById(student.getId()).orElseThrow().getUpdatedAt());
    new TransactionTemplate(transactions).executeWithoutResult(status -> {
      User previouslyManaged = users.findById(student.getId()).orElseThrow();
      assertEquals("原专业", previouslyManaged.getMajor());
      var result = service.rename(CurrentUser.from(administrator), original.getId(), new MajorService.RenameMajor("新专业"));
      assertEquals("新专业", result.name());
      assertEquals("新专业", users.findById(student.getId()).orElseThrow().getMajor());
    });
  }

  @Test void accountCreationBeforeRenameIsIncludedInBulkSynchronization() throws Exception {
    commitBeforeWaitingWrite(
        () -> accounts.create(CurrentUser.from(school),
            new UserService.CreateUser("createdbefore", "学生", Role.STUDENT, "原专业", null, COLLEGE, 2023)),
        () -> service.rename(CurrentUser.from(administrator), original.getId(), new MajorService.RenameMajor("新专业")));
    assertEquals("新专业", users.findByUsername("createdbefore").orElseThrow().getMajor());
  }

  @Test void independentAccountAssignmentsCanHoldSharedLocksConcurrently() throws Exception {
    try (var executor = Executors.newSingleThreadExecutor()) {
      new TransactionTemplate(transactions).executeWithoutResult(status -> {
        accounts.create(CurrentUser.from(school),
            new UserService.CreateUser("sharedfirst", "教师甲", Role.TEACHER, "原专业", null, COLLEGE, null));
        var second = executor.submit(() -> accounts.create(CurrentUser.from(administrator),
            new UserService.CreateUser("sharedsecond", "教师乙", Role.TEACHER, "原专业", null, COLLEGE, null)));
        assertDoesNotThrow(() -> second.get(5, TimeUnit.SECONDS));
      });
    }
    assertTrue(users.existsByUsername("sharedfirst"));
    assertTrue(users.existsByUsername("sharedsecond"));
  }

  @Test void accountCreationAfterRenameRejectsStaleOldMajor() throws Exception {
    commitBeforeWaitingWrite(
        () -> service.rename(CurrentUser.from(administrator), original.getId(), new MajorService.RenameMajor("新专业")),
        () -> assertInvalidMajor(() -> accounts.create(CurrentUser.from(school),
            new UserService.CreateUser("createdafter", "教师", Role.TEACHER, "原专业", null, COLLEGE, null))));
    assertFalse(users.existsByUsername("createdafter"));
  }

  @Test void teacherStudentCreationBeforeRenameIsIncludedWithoutReversingUserLocks() throws Exception {
    User teacher = profile("creatingteacher", Role.TEACHER, COLLEGE, "原专业", true);
    commitBeforeWaitingWrite(
        () -> accounts.createStudent(CurrentUser.from(teacher), new UserService.CreateStudent("teacherbefore", "学生", "原专业", 2023)),
        () -> service.rename(CurrentUser.from(administrator), original.getId(), new MajorService.RenameMajor("新专业")));
    User student = users.findByUsername("teacherbefore").orElseThrow();
    assertEquals("新专业", student.getMajor());
    assertEquals(teacher.getId(), student.getTeacherId());
  }

  @Test void teacherStudentCreationAfterRenameRejectsStaleOldMajor() throws Exception {
    User teacher = profile("creatingteacher", Role.TEACHER, COLLEGE, "原专业", true);
    commitBeforeWaitingWrite(
        () -> service.rename(CurrentUser.from(administrator), original.getId(), new MajorService.RenameMajor("新专业")),
        () -> assertInvalidMajor(() -> accounts.createStudent(CurrentUser.from(teacher),
            new UserService.CreateStudent("teacherafter", "学生", "原专业", 2023))));
    assertFalse(users.existsByUsername("teacherafter"));
  }

  @Test void accountEditBeforeRenameKeepsEditedFieldsAndGetsNewMajor() throws Exception {
    User student = profile("editedbefore", Role.STUDENT, COLLEGE, null, true);
    commitBeforeWaitingWrite(
        () -> accounts.update(CurrentUser.from(school), student.getId(),
            new UserService.UpdateUser("修改后的学生", "原专业", null, COLLEGE, 2024)),
        () -> service.rename(CurrentUser.from(administrator), original.getId(), new MajorService.RenameMajor("新专业")));
    User updated = users.findById(student.getId()).orElseThrow();
    assertEquals("新专业", updated.getMajor());
    assertEquals("修改后的学生", updated.getName());
    assertEquals(2024, updated.getGrade());
  }

  @Test void accountEditAfterRenameCannotWriteBackStaleMajorOrOtherFields() throws Exception {
    User student = profile("editedafter", Role.STUDENT, COLLEGE, "原专业", true);
    commitBeforeWaitingWrite(
        () -> service.rename(CurrentUser.from(administrator), original.getId(), new MajorService.RenameMajor("新专业")),
        () -> assertInvalidMajor(() -> accounts.update(CurrentUser.from(school), student.getId(),
            new UserService.UpdateUser("不应写入", "原专业", null, COLLEGE, 2024))));
    User updated = users.findById(student.getId()).orElseThrow();
    assertEquals("新专业", updated.getMajor());
    assertEquals(student.getName(), updated.getName());
    assertEquals(student.getGrade(), updated.getGrade());
  }

  @Test void passwordChangeBeforeRenamePreservesBothCommittedChanges() throws Exception {
    User teacher = passwordTeacher();
    commitBeforeWaitingWrite(
        () -> auth.changePassword(teacher.getId(), "Original123", "ChangedPassword1", "ChangedPassword1"),
        () -> service.rename(CurrentUser.from(administrator), original.getId(), new MajorService.RenameMajor("新专业")));
    assertPasswordAndMajor(teacher);
  }

  @Test void passwordChangeAfterRenamePreservesBothCommittedChanges() throws Exception {
    User teacher = passwordTeacher();
    commitBeforeWaitingWrite(
        () -> service.rename(CurrentUser.from(administrator), original.getId(), new MajorService.RenameMajor("新专业")),
        () -> auth.changePassword(teacher.getId(), "Original123", "ChangedPassword1", "ChangedPassword1"));
    assertPasswordAndMajor(teacher);
  }

  @Test void activeChangeWaitsForRenameAndPreservesBothCommittedChanges() throws Exception {
    var started = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      java.util.concurrent.Future<?>[] waiting = new java.util.concurrent.Future<?>[1];
      new TransactionTemplate(transactions).executeWithoutResult(status -> {
        checkedRename(school, "新专业");
        waiting[0] = executor.submit(() -> {
          started.countDown();
          service.setActive(CurrentUser.from(administrator), original.getId(), false);
        });
        assertBlocked(started, waiting[0]);
      });
      waiting[0].get(10, TimeUnit.SECONDS);
    }
    assertFinalDirectory();
  }

  @Test void renameWaitsForActiveChangeAndPreservesBothCommittedChanges() throws Exception {
    var started = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      java.util.concurrent.Future<?>[] waiting = new java.util.concurrent.Future<?>[1];
      new TransactionTemplate(transactions).executeWithoutResult(status -> {
        service.setActive(CurrentUser.from(administrator), original.getId(), false);
        waiting[0] = executor.submit(() -> {
          started.countDown();
          checkedRename(school, "新专业");
        });
        assertBlocked(started, waiting[0]);
      });
      waiting[0].get(10, TimeUnit.SECONDS);
    }
    assertFinalDirectory();
  }

  private void assertFinalDirectory() {
    MajorCatalog saved = majors.findById(original.getId()).orElseThrow();
    assertEquals("新专业", saved.getName());
    assertFalse(saved.isActive());
  }
  private void commitBeforeWaitingWrite(Runnable first, Runnable second) throws Exception {
    var started = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      java.util.concurrent.Future<?>[] waiting = new java.util.concurrent.Future<?>[1];
      new TransactionTemplate(transactions).executeWithoutResult(status -> {
        first.run();
        waiting[0] = executor.submit(() -> {
          started.countDown();
          second.run();
        });
        assertBlocked(started, waiting[0]);
      });
      waiting[0].get(10, TimeUnit.SECONDS);
    }
  }
  private User profile(String username, Role role, String college, String major, boolean active) {
    User user = new User(username, "hash", role, username, major, null, college, false, role == Role.STUDENT ? 2023 : null);
    user.setActive(active);
    User saved = users.saveAndFlush(user);
    return users.findById(saved.getId()).orElseThrow();
  }
  private User passwordTeacher() {
    return users.saveAndFlush(new User("passwordteacher", passwords.encode("Original123"), Role.TEACHER,
        "教师", "原专业", null, COLLEGE, false));
  }
  private void assertPasswordAndMajor(User teacher) {
    User updated = users.findById(teacher.getId()).orElseThrow();
    assertEquals("新专业", updated.getMajor());
    assertTrue(passwords.matches("ChangedPassword1", updated.getPasswordHash()));
  }
  private static void assertInvalidMajor(Runnable action) {
    assertEquals("INVALID_MAJOR", assertThrows(ApiException.class, action::run).code());
  }
  private static void assertUnrelatedFields(User before, User after) {
    assertEquals(before.getId(), after.getId());
    assertEquals(before.getUsername(), after.getUsername());
    assertEquals(before.getName(), after.getName());
    assertEquals(before.getRole(), after.getRole());
    assertEquals(before.getCollegeCode(), after.getCollegeCode());
    assertEquals(before.getTeacherId(), after.getTeacherId());
    assertEquals(before.getGrade(), after.getGrade());
    assertEquals(before.getPasswordHash(), after.getPasswordHash());
    assertEquals(before.isActive(), after.isActive());
    assertEquals(before.isMustChangePassword(), after.isMustChangePassword());
    assertEquals(before.getCreatedAt(), after.getCreatedAt());
  }
  private static void assertBlocked(CountDownLatch started, java.util.concurrent.Future<?> future) {
    try {
      assertTrue(started.await(5, TimeUnit.SECONDS));
      assertThrows(TimeoutException.class, () -> future.get(250, TimeUnit.MILLISECONDS));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
  private void checkedRename(User actor, String name) {
    try { rename(actor, name).andExpect(status().isOk()); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }
  private org.springframework.test.web.servlet.ResultActions rename(User actor, String name) throws Exception {
    var authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
        CurrentUser.from(actor), null, org.springframework.security.core.authority.AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
    authentication.setDetails(PasswordHasher.fingerprint(actor.getPasswordHash()));
    return mvc.perform(patch("/api/admin/majors/" + original.getId()).with(authentication(authentication)).with(csrf())
        .contentType("application/json").content(json.writeValueAsBytes(Map.of("name", name))));
  }
}
