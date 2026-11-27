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
import java.io.ByteArrayInputStream;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    jdbc.update("delete from submission_rule_issue");
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

  @Test void gradeFilterSeparatesKnownAndUnsetHistoricalStudents() throws Exception {
    var known = account("known", Role.STUDENT, teacher.getId(), true);
    var historical = account("historical", Role.STUDENT, teacher.getId(), true);
    jdbc.update("update sys_user set grade=2023 where id=?", known.getId());
    submission(known, "SUCCESS", 2, Instant.now());
    submission(historical, "SUCCESS", 4, Instant.now());
    var session = login("teacher");
    var knownData = json.readTree(mvc.perform(get("/api/teacher/statistics").param("grade", "2023")
        .session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
    assertStatistics(knownData, 1, 1, 1, 0, 1, 1, 0, 0, 0, 2L);
    var unknownData = json.readTree(mvc.perform(get("/api/teacher/statistics").param("grade", "UNSET")
        .session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
    assertStatistics(unknownData, 1, 1, 1, 0, 1, 1, 0, 0, 0, 4L);
    mvc.perform(get("/api/teacher/statistics").param("grade", "abc").session(session))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_GRADE"));
  }

  @Test void insightsUseLatestSuccessfulReviewsAndExposeIndexCoverage() throws Exception {
    var own = account("owninsight", Role.STUDENT, teacher.getId(), true);
    var historical = account("oldinsight", Role.STUDENT, teacher.getId(), true);
    var foreign = account("otherinsight", Role.STUDENT, otherTeacher.getId(), true);
    jdbc.update("update sys_user set grade=2023 where id in (?,?)", own.getId(), foreign.getId());
    Instant now = Instant.now();
    long old = submission(own, "SUCCESS", 8, now.minusSeconds(2 * 86400));
    index(old, "BODY-LINE", "正文行距应为1.5倍", 8);
    long current = submission(own, "SUCCESS", 2, now.minusSeconds(86400));
    index(current, "BODY-LINE", "正文行距应为1.5倍", 2);
    submission(own, "FAILED", 0, now);
    submission(historical, "SUCCESS", 0, now);
    long other = submission(foreign, "SUCCESS", 90, now);
    index(other, "FOREIGN", "其他教师的问题", 90);

    var session = login("teacher");
    var response = mvc.perform(get("/api/teacher/statistics/insights")
        .param("grade", "2023").param("range", "30").session(session))
        .andExpect(status().isOk()).andReturn();
    var data = json.readTree(response.getResponse().getContentAsByteArray()).path("data");
    assertEquals(1, data.path("coverage").path("eligibleReviews").asInt());
    assertEquals(1, data.path("coverage").path("indexedReviews").asInt());
    assertEquals("BODY-LINE", data.path("topRules").get(0).path("ruleId").asText());
    assertEquals(2, data.path("topRules").get(0).path("count").asInt());
    assertTrue(data.path("topRules").get(0).path("affectedStudents").isIntegralNumber());
    assertEquals(1, data.path("topRules").get(0).path("affectedStudents").asInt());
    assertEquals(1, data.path("problemDistribution").get(1).path("students").asInt());
    assertEquals(1, data.path("students").path("total").asInt());
    assertEquals("FAILED", data.path("students").path("items").get(0).path("latestReviewStatus").asText());
    assertEquals(2, data.path("students").path("items").get(0).path("latestProblemCount").asInt());
    assertEquals(2, data.path("heatmap").path("students").get(0).path("counts").path("BODY-LINE").asInt());
    assertEquals(30, data.path("dailySubmissions").size());
  }

  @Test void unknownHistoricalIndexIsNotCountedAsZeroAndExportHonorsGrade() throws Exception {
    var unknown = account("unindexed", Role.STUDENT, teacher.getId(), true);
    var known = account("knownexport", Role.STUDENT, teacher.getId(), true);
    jdbc.update("update sys_user set grade=2024 where id=?", known.getId());
    submission(unknown, "SUCCESS", 3, Instant.now());
    long reviewed = submission(known, "SUCCESS", 0, Instant.now());
    jdbc.update("update submission set rule_index_status='READY' where id=?", reviewed);
    var session = login("teacher");
    var data = json.readTree(mvc.perform(get("/api/teacher/statistics/insights")
        .param("range", "30").session(session)).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsByteArray()).path("data");
    assertEquals(2, data.path("coverage").path("eligibleReviews").asInt());
    assertEquals(1, data.path("coverage").path("indexedReviews").asInt());
    assertEquals(1, data.path("problemDistribution").get(0).path("students").asInt());
    var file = mvc.perform(get("/api/teacher/statistics/export")
        .param("grade", "2024").param("range", "30").session(session))
        .andExpect(status().isOk()).andReturn().getResponse();
    assertTrue(file.getContentType().contains("spreadsheetml"));
    try(var workbook = new XSSFWorkbook(new ByteArrayInputStream(file.getContentAsByteArray()))) {
      var sheet = workbook.getSheetAt(0);
      assertEquals(2, sheet.getPhysicalNumberOfRows());
      assertEquals("knownexport", sheet.getRow(1).getCell(0).getStringCellValue());
      assertEquals(0, sheet.getRow(1).getCell(5).getNumericCellValue());
    }
    var noReview = account("withoutreview", Role.STUDENT, teacher.getId(), true);
    jdbc.update("update sys_user set grade=2025 where id=?", noReview.getId());
    var blankFile = mvc.perform(get("/api/teacher/statistics/export")
        .param("grade", "2025").session(session))
        .andExpect(status().isOk()).andReturn().getResponse();
    try(var workbook = new XSSFWorkbook(new ByteArrayInputStream(blankFile.getContentAsByteArray()))) {
      assertNull(workbook.getSheetAt(0).getRow(1).getCell(5),
          "没有成功审核时，问题数单元格必须留空，不能写成0");
    }
    mvc.perform(get("/api/teacher/statistics/insights").param("grade", "UNSET").session(session))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.students.total").value(1));
    mvc.perform(get("/api/teacher/statistics/insights").param("range", "7").session(session))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_RANGE"));
    mvc.perform(get("/api/teacher/statistics/export").session(login("admin")))
        .andExpect(status().isForbidden());
  }

  @Test void insightsAggregateLatestRuleCountsAcrossStudentsWithoutForeignOrOlderReviews() throws Exception {
    var first = account("insightfirst", Role.STUDENT, teacher.getId(), true);
    var second = account("insightsecond", Role.STUDENT, teacher.getId(), true);
    var foreign = account("insightforeign", Role.STUDENT, otherTeacher.getId(), true);
    Instant now = Instant.now();
    long old = submission(first, "SUCCESS", 50, now.minusSeconds(2 * 86400));
    index(old, "BODY-LINE", "正文行距应为1.5倍", 50);
    long current = submission(first, "SUCCESS", 3, now.minusSeconds(86400));
    index(current, "BODY-LINE", "正文行距应为1.5倍", 2);
    index(current, "FIG-CAPTION", "图题字号应为五号", 1);
    long secondReview = submission(second, "SUCCESS", 7, now);
    index(secondReview, "BODY-LINE", "正文行距应为1.5倍", 3);
    index(secondReview, "REF-ORDER", "参考文献顺序应与引用一致", 4);
    long foreignReview = submission(foreign, "SUCCESS", 100, now);
    index(foreignReview, "BODY-LINE", "正文行距应为1.5倍", 100);

    var data = json.readTree(mvc.perform(get("/api/teacher/statistics/insights")
        .param("range", "30").session(login("teacher"))).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsByteArray()).path("data");
    assertEquals(2, data.path("coverage").path("eligibleReviews").asInt());
    assertEquals(2, data.path("coverage").path("indexedReviews").asInt());
    assertEquals("BODY-LINE", data.path("topRules").get(0).path("ruleId").asText());
    assertEquals(5, data.path("topRules").get(0).path("count").asInt());
    assertRuleReach(data, "BODY-LINE", 5, 2);
    assertRuleReach(data, "REF-ORDER", 4, 1);
    assertRuleReach(data, "FIG-CAPTION", 1, 1);
    assertEquals(1, data.path("problemDistribution").get(1).path("students").asInt());
    assertEquals(1, data.path("problemDistribution").get(2).path("students").asInt());
    assertEquals(second.getId().toString(), data.path("heatmap").path("students").get(0)
        .path("studentId").asText());
    assertEquals(3, data.path("heatmap").path("students").get(0)
        .path("counts").path("BODY-LINE").asInt());
    assertEquals(4, data.path("heatmap").path("students").get(0)
        .path("counts").path("REF-ORDER").asInt());
  }

  @ParameterizedTest
  @ValueSource(strings = {"PENDING", "UNAVAILABLE", "READY"})
  void newestUnindexedOrZeroSuccessDoesNotReviveOlderIndexedRuleReach(String latestIndexStatus) throws Exception {
    var known = account("reachknown", Role.STUDENT, teacher.getId(), true);
    var replaced = account("reachreplaced", Role.STUDENT, teacher.getId(), true);
    jdbc.update("update sys_user set grade=2023 where id in (?,?)", known.getId(), replaced.getId());
    Instant now = Instant.now();
    long knownSuccess = submission(known, "SUCCESS", 3, now.minusSeconds(3600));
    index(knownSuccess, "BODY-LINE", "正文行距应为1.5倍", 3);
    submission(known, "FAILED", 0, now);
    long previous = submission(replaced, "SUCCESS", 77, now.minusSeconds(7200));
    index(previous, "BODY-LINE", "正文行距应为1.5倍", 77);
    // The larger id wins for identical timestamps. READY + zero problems has no issue rows.
    long newest = submission(replaced, "SUCCESS", latestIndexStatus.equals("READY") ? 0 : 5,
        now.minusSeconds(7200));
    jdbc.update("update submission set rule_index_status=? where id=?", latestIndexStatus, newest);

    JsonNode data = insights(login("teacher"), "2023", "30");
    assertEquals(1, data.path("topRules").size());
    assertRuleReach(data, "BODY-LINE", 3, 1);
    assertEquals(2, data.path("coverage").path("eligibleReviews").asLong());
    assertEquals(latestIndexStatus.equals("READY") ? 2 : 1,
        data.path("coverage").path("indexedReviews").asLong());
  }

  @Test void reachCountsHonorCurrentTeacherGradeAndDateFiltersAcrossTheWholeRoster() throws Exception {
    var recent = account("reachrecent", Role.STUDENT, teacher.getId(), true);
    var older = account("reacholder", Role.STUDENT, teacher.getId(), true);
    var anotherGrade = account("reachgrade2024", Role.STUDENT, teacher.getId(), true);
    var unset = account("reachunset", Role.STUDENT, teacher.getId(), true);
    var foreign = account("reachforeign", Role.STUDENT, otherTeacher.getId(), true);
    jdbc.update("update sys_user set grade=2023 where id in (?,?,?)", recent.getId(), older.getId(), foreign.getId());
    jdbc.update("update sys_user set grade=2024 where id=?", anotherGrade.getId());
    Instant now = Instant.now();
    index(submission(recent, "SUCCESS", 4, now), "BODY-LINE", "正文行距应为1.5倍", 4);
    index(submission(older, "SUCCESS", 50, now.minusSeconds(45 * 86400)), "BODY-LINE", "正文行距应为1.5倍", 50);
    index(submission(anotherGrade, "SUCCESS", 11, now), "BODY-LINE", "正文行距应为1.5倍", 11);
    index(submission(unset, "SUCCESS", 13, now), "BODY-LINE", "正文行距应为1.5倍", 13);
    index(submission(foreign, "SUCCESS", 100, now), "BODY-LINE", "正文行距应为1.5倍", 100);

    var session = login("teacher");
    assertRuleReach(insights(session, "2023", "30"), "BODY-LINE", 4, 1);
    assertRuleReach(insights(session, "2023", "90"), "BODY-LINE", 54, 2);
    assertRuleReach(insights(session, null, "30"), "BODY-LINE", 28, 3);
    assertRuleReach(insights(session, "UNSET", "30"), "BODY-LINE", 13, 1);
    JsonNode anotherPage = json.readTree(mvc.perform(get("/api/teacher/statistics/insights")
        .param("grade", "2023").param("range", "30").param("page", "2").param("size", "1").session(session))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
    assertRuleReach(anotherPage, "BODY-LINE", 4, 1);
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

  private long submission(User student, String state, int problems, Instant time) {
    String directory = "statistics/" + java.util.UUID.randomUUID();
    jdbc.update("insert into submission(student_id, original_filename, file_size, status, storage_dir, problem_count, attempts, created_at, updated_at) values (?, 'paper.docx', 1, ?, ?, ?, 0, ?, ?)",
        student.getId(), state, directory, problems,
        java.sql.Timestamp.from(time), java.sql.Timestamp.from(time));
    return jdbc.queryForObject("select id from submission where storage_dir=?", Long.class, directory);
  }

  private void index(long submissionId, String ruleId, String description, int count) {
    jdbc.update("update submission set rule_index_status='READY' where id=?", submissionId);
    jdbc.update("insert into submission_rule_issue(submission_id, rule_id, description, issue_count) values (?,?,?,?)",
        submissionId, ruleId, description, count);
  }

  private JsonNode statistics(MockHttpSession session) throws Exception {
    var response = mvc.perform(get("/api/teacher/statistics").session(session)).andExpect(status().isOk()).andReturn();
    return json.readTree(response.getResponse().getContentAsByteArray()).path("data");
  }

  private JsonNode insights(MockHttpSession session, String grade, String range) throws Exception {
    var request = get("/api/teacher/statistics/insights").param("range", range).session(session);
    if (grade != null) request.param("grade", grade);
    return json.readTree(mvc.perform(request).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsByteArray()).path("data");
  }

  private static void assertRuleReach(JsonNode data, String ruleId, long count, long affectedStudents) {
    for (JsonNode rule : data.path("topRules")) {
      if (!ruleId.equals(rule.path("ruleId").asText())) continue;
      assertTrue(rule.path("count").isIntegralNumber(), rule.toString());
      assertTrue(rule.path("affectedStudents").isIntegralNumber(), rule.toString());
      assertEquals(count, rule.path("count").asLong());
      assertEquals(affectedStudents, rule.path("affectedStudents").asLong());
      return;
    }
    fail("Expected ranked rule " + ruleId + " in " + data.path("topRules"));
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
