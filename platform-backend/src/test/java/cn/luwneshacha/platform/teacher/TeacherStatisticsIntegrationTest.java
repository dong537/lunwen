package cn.luwneshacha.platform.teacher;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.luwneshacha.platform.account.Role;
import cn.luwneshacha.platform.account.User;
import cn.luwneshacha.platform.account.UserRepository;
import cn.luwneshacha.platform.auth.CurrentUser;
import cn.luwneshacha.platform.auth.PasswordHasher;
import cn.luwneshacha.platform.common.ApiException;
import cn.luwneshacha.platform.submission.Submission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(classes = TeacherStatisticsIntegrationTest.TestApplication.class,
    properties = "platform.bootstrap.enabled=false")
@AutoConfigureMockMvc
class TeacherStatisticsIntegrationTest {
  @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
  @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
    properties.add("spring.datasource.username", DATABASE::getUsername);
    properties.add("spring.datasource.password", DATABASE::getPassword);
  }

  @SpringBootConfiguration @EnableAutoConfiguration
  @ComponentScan(basePackages = {"cn.luwneshacha.platform.account", "cn.luwneshacha.platform.auth",
      "cn.luwneshacha.platform.common", "cn.luwneshacha.platform.teacher"},
      excludeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = SpringBootConfiguration.class))
  @EntityScan(basePackageClasses = {User.class, Submission.class})
  @EnableJpaRepositories({"cn.luwneshacha.platform.account", "cn.luwneshacha.platform.submission",
      "cn.luwneshacha.platform.teacher"})
  static class TestApplication {}

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired UserRepository users;
  @Autowired PasswordHasher passwords;
  @Autowired TeacherStatisticsService service;
  @Autowired JdbcTemplate jdbc;
  User teacher, otherTeacher, admin, student;

  @BeforeEach void seed() {
    jdbc.update("delete from submission");
    jdbc.update("delete from sys_user where role='STUDENT'");
    users.deleteAll();
    teacher = account("teacher", Role.TEACHER, null, true);
    otherTeacher = account("otherteacher", Role.TEACHER, null, true);
    admin = account("admin", Role.ADMIN, null, true);
    student = account("reader", Role.STUDENT, null, true);
  }

  @Test void emptyTeacherHasZeroCountsAndUnknownProblemTotal() throws Exception {
    var data = statistics(login("teacher"));
    assertStatistics(data, 0, 0, 0, 0, 0, 0, 0, 0, 0, null);
  }

  @Test void countsCurrentRosterAndUsesLatestSuccessfulReviewPerStudent() throws Exception {
    var first = account("first", Role.STUDENT, teacher.getId(), true);
    var inactive = account("inactive", Role.STUDENT, teacher.getId(), false);
    account("untouched", Role.STUDENT, teacher.getId(), true);
    var failedOnly = account("failedonly", Role.STUDENT, teacher.getId(), true);
    var foreign = account("foreign", Role.STUDENT, otherTeacher.getId(), true);
    Instant sameTime = Instant.parse("2026-11-01T00:00:00Z");
    submission(first, "SUCCESS", 9, sameTime);
    submission(first, "SUCCESS", 2, sameTime); // same timestamp: larger id is the latest success
    submission(first, "FAILED", 0, sameTime.plusSeconds(10));
    submission(first, "PROCESSING", 0, sameTime.plusSeconds(20));
    submission(inactive, "SUCCESS", 4, sameTime);
    submission(inactive, "PENDING", 0, sameTime.plusSeconds(30));
    submission(failedOnly, "FAILED", 0, sameTime);
    submission(foreign, "SUCCESS", 700, sameTime);

    var data = statistics(login("teacher"));
    assertStatistics(data, 4, 3, 3, 1, 2, 3, 1, 1, 2, 6L);
  }

  @Test void zeroProblemSuccessIsZeroAndLatestFailureDoesNotEraseSuccess() throws Exception {
    var reviewed = account("reviewed", Role.STUDENT, teacher.getId(), true);
    submission(reviewed, "SUCCESS", 0, Instant.parse("2026-11-01T00:00:00Z"));
    submission(reviewed, "FAILED", 0, Instant.parse("2026-11-02T00:00:00Z"));
    assertStatistics(statistics(login("teacher")), 1, 1, 1, 0, 1, 1, 0, 0, 1, 0L);
  }

  @Test void currentTeacherReassignmentChangesStatisticsImmediately() throws Exception {
    var reassigned = account("reassigned", Role.STUDENT, teacher.getId(), true);
    submission(reassigned, "SUCCESS", 3, Instant.now());
    var firstSession = login("teacher");
    var secondSession = login("otherteacher");
    assertEquals(1, statistics(firstSession).path("totalStudents").asLong());
    jdbc.update("update sys_user set teacher_id=? where id=?", otherTeacher.getId(), reassigned.getId());
    assertEquals(0, statistics(firstSession).path("totalStudents").asLong());
    assertEquals(3, statistics(secondSession).path("latestSuccessfulProblemCount").asLong());
  }

  @Test void rejectsUnauthorizedRolesAndMissingLogin() throws Exception {
    mvc.perform(get("/api/teacher/statistics")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/teacher/statistics").session(login("reader"))).andExpect(status().isForbidden());
    mvc.perform(get("/api/teacher/statistics").session(login("admin"))).andExpect(status().isForbidden());
    var missing = assertThrows(ApiException.class, () -> service.statistics(null));
    assertEquals(401, missing.status().value());
    var forgedRole = assertThrows(ApiException.class, () -> service.statistics(
        new CurrentUser(student.getId(), student.getUsername(), Role.TEACHER, student.getName(), false)));
    assertEquals(403, forgedRole.status().value());
  }

  @Test void sessionGuardRejectsDisabledTeacherAndRequiresPasswordChange() throws Exception {
    var session = login("teacher");
    jdbc.update("update sys_user set must_change_password=true where id=?", teacher.getId());
    mvc.perform(get("/api/teacher/statistics").session(session)).andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("PASSWORD_CHANGE_REQUIRED"));
    jdbc.update("update sys_user set must_change_password=false where id=?", teacher.getId());
    session = login("teacher");
    jdbc.update("update sys_user set is_active=false where id=?", teacher.getId());
    mvc.perform(get("/api/teacher/statistics").session(session)).andExpect(status().isUnauthorized());
  }

  @Test void neverUsesClientSuppliedTeacherId() throws Exception {
    var ownStudent = account("ownstudent", Role.STUDENT, teacher.getId(), true);
    account("foreignstudent", Role.STUDENT, otherTeacher.getId(), true);
    submission(ownStudent, "SUCCESS", 2, Instant.now());
    var session = login("teacher");
    mvc.perform(get("/api/teacher/statistics").param("teacherId", otherTeacher.getId().toString()).session(session))
        .andExpect(status().isOk());
    assertEquals(1, statistics(session).path("totalStudents").asLong());
  }

  private User account(String username, Role role, Long teacherId, boolean active) {
    var user = new User(username, passwords.encode("TestPassword1"), role, username, null, teacherId, false);
    user.setActive(active);
    return users.saveAndFlush(user);
  }

  private void submission(User student, String state, int problems, Instant time) {
    jdbc.update("insert into submission(student_id, original_filename, file_size, status, storage_dir, problem_count, attempts, created_at, updated_at) values (?, 'paper.docx', 1, ?, ?, ?, 0, ?, ?)",
        student.getId(), state, "statistics/" + java.util.UUID.randomUUID(), problems,
        java.sql.Timestamp.from(time), java.sql.Timestamp.from(time));
  }

  private JsonNode statistics(MockHttpSession session) throws Exception {
    var response = mvc.perform(get("/api/teacher/statistics").session(session)).andExpect(status().isOk()).andReturn();
    return json.readTree(response.getResponse().getContentAsByteArray()).path("data");
  }

  private static void assertStatistics(JsonNode value, long total, long active, long submitted, long notSubmitted,
      long successfulStudents, long successes, long pending, long processing, long failed, Long problems) {
    assertEquals(10, value.size(), value.toString());
    assertTrue(value.path("totalStudents").isIntegralNumber(), value.toString());
    assertTrue(value.path("activeStudents").isIntegralNumber(), value.toString());
    assertTrue(value.path("submittedStudents").isIntegralNumber(), value.toString());
    assertTrue(value.path("notSubmittedStudents").isIntegralNumber(), value.toString());
    assertTrue(value.path("studentsWithSuccessfulReview").isIntegralNumber(), value.toString());
    assertTrue(value.path("successfulSubmissions").isIntegralNumber(), value.toString());
    assertTrue(value.path("pendingSubmissions").isIntegralNumber(), value.toString());
    assertTrue(value.path("processingSubmissions").isIntegralNumber(), value.toString());
    assertTrue(value.path("failedSubmissions").isIntegralNumber(), value.toString());
    assertEquals(total, value.path("totalStudents").asLong());
    assertEquals(active, value.path("activeStudents").asLong());
    assertEquals(submitted, value.path("submittedStudents").asLong());
    assertEquals(notSubmitted, value.path("notSubmittedStudents").asLong());
    assertEquals(successfulStudents, value.path("studentsWithSuccessfulReview").asLong());
    assertEquals(successes, value.path("successfulSubmissions").asLong());
    assertEquals(pending, value.path("pendingSubmissions").asLong());
    assertEquals(processing, value.path("processingSubmissions").asLong());
    assertEquals(failed, value.path("failedSubmissions").asLong());
    if (problems == null) assertTrue(value.path("latestSuccessfulProblemCount").isNull(), value.toString());
    else assertEquals(problems.longValue(), value.path("latestSuccessfulProblemCount").asLong());
  }

  private MockHttpSession login(String username) throws Exception {
    var csrf = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
    var data = json.readTree(csrf.getResponse().getContentAsByteArray()).path("data");
    var session = (MockHttpSession) csrf.getRequest().getSession(false);
    var result = mvc.perform(post("/api/auth/login").session(session)
        .header(data.path("headerName").asText(), data.path("token").asText())
        .contentType("application/json").content(json.writeValueAsBytes(Map.of("username", username,
            "password", "TestPassword1")))).andExpect(status().isOk()).andReturn();
    return (MockHttpSession) result.getRequest().getSession(false);
  }
}
