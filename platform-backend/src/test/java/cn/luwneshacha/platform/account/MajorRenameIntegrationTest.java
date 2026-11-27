package cn.luwneshacha.platform.account;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.luwneshacha.platform.auth.CurrentUser;
import cn.luwneshacha.platform.auth.PasswordHasher;
import cn.luwneshacha.platform.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
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
  @ComponentScan({"cn.luwneshacha.platform.account", "cn.luwneshacha.platform.auth", "cn.luwneshacha.platform.common"})
  @EntityScan({"cn.luwneshacha.platform.account", "cn.luwneshacha.platform.submission"})
  @EnableJpaRepositories({"cn.luwneshacha.platform.account", "cn.luwneshacha.platform.submission"})
  static class TestApplication {}

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired UserRepository users;
  @Autowired UserService accounts;
  @Autowired MajorCatalogRepository majors;
  @Autowired MajorService service;
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

  @Test void persistsOnlyDirectoryRenameAndKeepsExistingAccountProfiles() throws Exception {
    User student = users.saveAndFlush(new User("student01", "hash", Role.STUDENT, "学生", "原专业", null, COLLEGE, false, 2023));
    User teacher = users.saveAndFlush(new User("teacher01", "hash", Role.TEACHER, "教师", "原专业", null, COLLEGE, false));
    User other = users.saveAndFlush(new User("student02", "hash", Role.STUDENT, "其他学院学生", "原专业", null,
        College.EDUCATION_ARTS.name(), false, 2023));
    var timestamps = java.util.List.of(student, teacher, other).stream().collect(java.util.stream.Collectors.toMap(
        User::getId, user -> users.findById(user.getId()).orElseThrow().getUpdatedAt()));
    service.setActive(CurrentUser.from(school), original.getId(), false);
    rename(administrator, "  新专业  ").andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(original.getId().toString()))
        .andExpect(jsonPath("$.data.active").value(false));
    MajorCatalog saved = majors.findById(original.getId()).orElseThrow();
    assertEquals("新专业", saved.getName());
    assertEquals(COLLEGE, saved.getCollegeCode());
    assertFalse(saved.isActive());
    for (User user : java.util.List.of(student, teacher, other)) {
      User unchanged = users.findById(user.getId()).orElseThrow();
      assertEquals("原专业", unchanged.getMajor());
      assertEquals(timestamps.get(user.getId()), unchanged.getUpdatedAt());
    }
    var error = assertThrows(ApiException.class, () -> accounts.create(CurrentUser.from(school),
        new UserService.CreateUser("newteacher", "新教师", Role.TEACHER, "原专业", null, COLLEGE, null)));
    assertEquals("INVALID_MAJOR", error.code());
    assertEquals("原专业", accounts.update(CurrentUser.from(school), student.getId(),
        new UserService.UpdateUser("学生改名", "原专业", null, COLLEGE, 2023)).major());
  }

  @Test void invalidAndDuplicateNamesDoNotChangeStoredDirectory() throws Exception {
    MajorCatalog duplicate = new MajorCatalog(COLLEGE, "已有专业");
    duplicate.setActive(false);
    majors.saveAndFlush(duplicate);
    rename(administrator, "已有专业").andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("MAJOR_EXISTS"));
    rename(administrator, "   ").andExpect(status().isBadRequest());
    rename(administrator, "专".repeat(65)).andExpect(status().isBadRequest());
    assertEquals("原专业", majors.findById(original.getId()).orElseThrow().getName());
    assertTrue(majors.findById(original.getId()).orElseThrow().isActive());
  }

  @Test void enclosingTransactionFailureRollsBackRename() {
    assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
      checkedRename(school, "应当回滚");
      throw new IllegalStateException("Force rollback after the real directory update");
    }));
    assertEquals("原专业", majors.findById(original.getId()).orElseThrow().getName());
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
