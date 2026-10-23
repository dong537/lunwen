package cn.luwneshacha.platform.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

import cn.luwneshacha.platform.auth.PasswordHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.mock.web.MockMultipartFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(classes = AccountSecurityIntegrationTest.TestApplication.class,
    properties = {"platform.bootstrap.enabled=false"})
@AutoConfigureMockMvc
class AccountSecurityIntegrationTest {
  @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
  @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
    properties.add("spring.datasource.username", DATABASE::getUsername);
    properties.add("spring.datasource.password", DATABASE::getPassword);
  }
  @SpringBootConfiguration @EnableAutoConfiguration
  @ComponentScan({"cn.luwneshacha.platform.account", "cn.luwneshacha.platform.auth",
      "cn.luwneshacha.platform.common", "cn.luwneshacha.platform.importing"})
  @EntityScan("cn.luwneshacha.platform.account")
  @EnableJpaRepositories("cn.luwneshacha.platform.account")
  static class TestApplication {}

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired UserRepository users;
  @Autowired PasswordHasher passwords;
  @Autowired UserService accounts;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  User student;

  @BeforeEach void seed() {
    users.findAll().stream().filter(u -> u.getRole() == Role.STUDENT).forEach(users::delete);
    users.deleteAll();
    users.save(new User("admin", passwords.encode("AdminPassword1"), Role.ADMIN, "管理员", null, null, false));
    student = users.save(new User("2026001", passwords.encode("123456"), Role.STUDENT, "测试学生", null, null, true));
  }

  @Test void importPreviewDoesNotWriteAndExecutionImportsOnlyValidNewAccounts() throws Exception {
    var teacher = users.save(new User("teacher01", passwords.encode("123456"), Role.TEACHER, "指导老师", null, null, true));
    var file = workbook(Role.STUDENT, new String[][] {{"new001", "新学生", "计算机", "teacher01"},
        {"2026001", "不可覆盖的名字", "新专业", "teacher01"}, {"bad001", "", "", ""},
        {"bad002", "学生", "", "missing"}});
    var admin = login("admin", "AdminPassword1");
    var csrf = csrf(admin);
    mvc.perform(multipart("/api/admin/imports/preview").file(file).param("role", "STUDENT")
        .session(admin).header(csrf.header(), csrf.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalRows").value(4)).andExpect(jsonPath("$.data.validRows").value(1))
        .andExpect(jsonPath("$.data.successCount").value(0)).andExpect(jsonPath("$.data.skippedCount").value(1))
        .andExpect(jsonPath("$.data.failedCount").value(2));
    assertFalse(users.existsByUsername("new001"));
    mvc.perform(multipart("/api/admin/imports/execute").file(file).param("role", "STUDENT")
        .session(admin).header(csrf.header(), csrf.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.successCount").value(1)).andExpect(jsonPath("$.data.failedCount").value(2));
    var imported = users.findByUsername("new001").orElseThrow();
    assertEquals(teacher.getId(), imported.getTeacherId());
    assertTrue(imported.isMustChangePassword());
    assertTrue(passwords.matches("123456", imported.getPasswordHash()));
    assertEquals("测试学生", users.findByUsername("2026001").orElseThrow().getName());
    mvc.perform(multipart("/api/admin/imports/execute").file(file).param("role", "STUDENT")
        .session(admin).header(csrf.header(), csrf.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.successCount").value(0)).andExpect(jsonPath("$.data.skippedCount").value(2));
  }

  @Test void importsOneHundredStudentsAndRepeatingTheFileSkipsEveryAccount() throws Exception {
    var teacher = users.save(new User("teacher100", passwords.encode("123456"), Role.TEACHER, "批量导入导师", null, null, true));
    var rows = java.util.stream.IntStream.range(0, 100)
        .mapToObj(i -> new String[] {"202610" + String.format(java.util.Locale.ROOT, "%03d", i), "导入测试学生" + i, "计算机科学", "teacher100"})
        .toArray(String[][]::new);
    var file = workbook(Role.STUDENT, rows);
    var admin = login("admin", "AdminPassword1"); var csrf = csrf(admin);
    mvc.perform(multipart("/api/admin/imports/execute").file(file).param("role", "STUDENT")
        .session(admin).header(csrf.header(), csrf.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalRows").value(100)).andExpect(jsonPath("$.data.successCount").value(100))
        .andExpect(jsonPath("$.data.skippedCount").value(0)).andExpect(jsonPath("$.data.failedCount").value(0));
    var imported = users.findAll().stream().filter(u -> u.getUsername().startsWith("202610")).toList();
    assertEquals(100, imported.size());
    assertTrue(imported.stream().allMatch(u -> u.getTeacherId().equals(teacher.getId()) && u.isMustChangePassword() && u.isActive()));
    var identities = imported.stream().collect(java.util.stream.Collectors.toMap(User::getUsername, User::getId));
    mvc.perform(multipart("/api/admin/imports/execute").file(file).param("role", "STUDENT")
        .session(admin).header(csrf.header(), csrf.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalRows").value(100)).andExpect(jsonPath("$.data.successCount").value(0))
        .andExpect(jsonPath("$.data.skippedCount").value(100)).andExpect(jsonPath("$.data.failedCount").value(0));
    assertEquals(identities, users.findAll().stream().filter(u -> u.getUsername().startsWith("202610"))
        .collect(java.util.stream.Collectors.toMap(User::getUsername, User::getId)));
  }

  @Test void importRejectsAllDuplicateRowsAndRechecksTeacherOnExecution() throws Exception {
    var teacher = users.save(new User("teacher01", passwords.encode("123456"), Role.TEACHER, "教师", null, null, false));
    var file = workbook(Role.STUDENT, new String[][] {{"dup001", "甲", "", ""}, {"dup001", "乙", "", ""},
        {"new002", "丙", "", "teacher01"}});
    var admin = login("admin", "AdminPassword1");
    var csrf = csrf(admin);
    mvc.perform(multipart("/api/admin/imports/preview").file(file).param("role", "STUDENT")
        .session(admin).header(csrf.header(), csrf.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.validRows").value(1)).andExpect(jsonPath("$.data.failedCount").value(2));
    teacher.setActive(false); users.saveAndFlush(teacher);
    mvc.perform(multipart("/api/admin/imports/execute").file(file).param("role", "STUDENT")
        .session(admin).header(csrf.header(), csrf.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.successCount").value(0)).andExpect(jsonPath("$.data.failedCount").value(3));
    assertFalse(users.existsByUsername("dup001")); assertFalse(users.existsByUsername("new002"));
  }

  @Test void importEnforcesFileAndRowLimitsAndTemplateIsReadable() throws Exception {
    var admin = login("admin", "AdminPassword1"); var csrf = csrf(admin);
    var template = mvc.perform(get("/api/admin/imports/template").param("role", "STUDENT").session(admin))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    try (var book = new XSSFWorkbook(new ByteArrayInputStream(template))) {
      assertEquals("学号", book.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
      assertEquals("指导教师工号", book.getSheetAt(0).getRow(0).getCell(3).getStringCellValue());
      assertEquals("@", book.getSheetAt(0).getColumnStyle(0).getDataFormatString());
    }
    String[][] tooMany = java.util.stream.IntStream.range(0, 2001).mapToObj(i -> new String[] {"user" + i, "姓名"}).toArray(String[][]::new);
    mvc.perform(multipart("/api/admin/imports/preview").file(workbook(Role.TEACHER, tooMany)).param("role", "TEACHER")
        .session(admin).header(csrf.header(), csrf.token())).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("IMPORT_ROW_LIMIT"));
    mvc.perform(multipart("/api/admin/imports/preview").file(new MockMultipartFile("file", "users.xlsx", "application/octet-stream", new byte[5 * 1024 * 1024 + 1]))
        .param("role", "TEACHER").session(admin).header(csrf.header(), csrf.token())).andExpect(status().isPayloadTooLarge());
    mvc.perform(multipart("/api/admin/imports/preview").file(new MockMultipartFile("file", "users.csv", "text/csv", new byte[] {1}))
        .param("role", "TEACHER").session(admin).header(csrf.header(), csrf.token())).andExpect(status().isBadRequest());
    mvc.perform(get("/api/admin/imports/template").param("role", "ADMIN").session(admin)).andExpect(status().isBadRequest());
  }

  @Test void teacherManagementWarnsAboutAssignedStudentsAndKeepsAdministratorImmutable() throws Exception {
    var admin = login("admin", "AdminPassword1"); var csrf = csrf(admin);
    var created = mvc.perform(post("/api/admin/users").session(admin).header(csrf.header(), csrf.token())
        .contentType("application/json").content("{\"username\":\"teacher02\",\"name\":\"教师\",\"role\":\"TEACHER\"}"))
        .andExpect(status().isOk()).andReturn();
    String teacherId = json.readTree(created.getResponse().getContentAsByteArray()).path("data").path("id").asText();
    mvc.perform(patch("/api/admin/users/" + student.getId()).session(admin).header(csrf.header(), csrf.token())
        .contentType("application/json").content(json.writeValueAsBytes(Map.of("name", "学生", "teacherId", teacherId))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.teacherName").value("教师"));
    mvc.perform(post("/api/admin/users/" + teacherId + "/active").session(admin).header(csrf.header(), csrf.token())
        .contentType("application/json").content("{\"active\":false}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.assignedStudents").value(1))
        .andExpect(jsonPath("$.data.message").isNotEmpty());
    long adminId = users.findByUsername("admin").orElseThrow().getId();
    mvc.perform(post("/api/admin/users/" + adminId + "/reset-password").session(admin).header(csrf.header(), csrf.token()))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/admin/users").session(admin).param("role", "STUDENT").param("page", "0"))
        .andExpect(status().isBadRequest());
  }

  @Test void concurrentProfileAndPasswordResetPreserveBothChanges() throws Exception {
    var updated = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var resetting = new java.util.concurrent.CountDownLatch(1);
    String initialHash = student.getPasswordHash();
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var profile = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> {
        accounts.update(student.getId(), new UserService.UpdateUser("并发修改后的名字", "计算机", null));
        updated.countDown();
        try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        return null;
      }));
      assertTrue(updated.await(5, java.util.concurrent.TimeUnit.SECONDS));
      var reset = executor.submit(() -> { resetting.countDown(); return accounts.resetPassword(student.getId()); });
      assertTrue(resetting.await(5, java.util.concurrent.TimeUnit.SECONDS));
      try { assertThrows(java.util.concurrent.TimeoutException.class, () -> reset.get(1, java.util.concurrent.TimeUnit.SECONDS)); }
      finally { release.countDown(); }
      profile.get(5, java.util.concurrent.TimeUnit.SECONDS); reset.get(5, java.util.concurrent.TimeUnit.SECONDS);
    } finally { release.countDown(); }
    var result = users.findById(student.getId()).orElseThrow();
    assertEquals("并发修改后的名字", result.getName());
    assertEquals("计算机", result.getMajor());
    assertNotEquals(initialHash, result.getPasswordHash());
    assertTrue(result.isMustChangePassword());
    assertTrue(passwords.matches("123456", result.getPasswordHash()));
  }

  private MockMultipartFile workbook(Role role, String[][] rows) throws Exception {
    try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
      var sheet = book.createSheet("账号"); var header = sheet.createRow(0);
      String[] columns = role == Role.STUDENT ? new String[] {"学号", "姓名", "专业", "指导教师工号"} : new String[] {"工号", "姓名"};
      for (int i = 0; i < columns.length; i++) header.createCell(i).setCellValue(columns[i]);
      for (int i = 0; i < rows.length; i++) {
        var row = sheet.createRow(i + 1);
        for (int j = 0; j < rows[i].length; j++) row.createCell(j).setCellValue(rows[i][j]);
      }
      book.write(output);
      return new MockMultipartFile("file", "users.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
    }
  }

  @Test void firstLoginCannotUseBusinessRoutesUntilPasswordChanges() throws Exception {
    var session = login("2026001", "123456");
    mvc.perform(get("/api/admin/users").session(session)).andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("PASSWORD_CHANGE_REQUIRED"));
    var csrf = csrf(session);
    mvc.perform(post("/api/auth/password").session(session).header(csrf.header(), csrf.token())
        .contentType("application/json").content(json.writeValueAsBytes(Map.of("oldPassword", "123456",
            "newPassword", "论文".repeat(50), "confirmPassword", "论文".repeat(50)))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.mustChangePassword").value(false));
    mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    mvc.perform(get("/api/admin/users").session(session)).andExpect(status().isForbidden());
    assertTrue(passwords.matches("论文".repeat(50), users.findById(student.getId()).orElseThrow().getPasswordHash()));
  }

  @Test void passwordResetInvalidatesExistingSessionsImmediately() throws Exception {
    var studentSession = login("2026001", "123456");
    var admin = login("admin", "AdminPassword1");
    var csrf = csrf(admin);
    mvc.perform(post("/api/admin/users/" + student.getId() + "/reset-password").session(admin)
        .header(csrf.header(), csrf.token())).andExpect(status().isOk());
    mvc.perform(get("/api/auth/me").session(studentSession)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/auth/me").session(login("2026001", "123456"))).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.mustChangePassword").value(true));
  }

  @Test void ownPasswordChangeRevokesOtherSessionsAndAllowsFetchingFreshCsrf() throws Exception {
    var current = login("2026001", "123456");
    var other = login("2026001", "123456"); var oldCsrf = csrf(other);
    var csrf = csrf(current);
    mvc.perform(post("/api/auth/password").session(current).header(csrf.header(), csrf.token())
        .contentType("application/json").content(json.writeValueAsBytes(Map.of("oldPassword", "123456",
            "newPassword", "NewPassword1", "confirmPassword", "NewPassword1"))))
        .andExpect(status().isOk());
    var refreshed = mvc.perform(get("/api/auth/csrf").session(other)).andExpect(status().isOk()).andReturn();
    assertNotEquals(oldCsrf.token(), json.readTree(refreshed.getResponse().getContentAsByteArray()).path("data").path("token").asText());
    mvc.perform(get("/api/auth/me").session((MockHttpSession) refreshed.getRequest().getSession(false)))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/auth/me").session(current)).andExpect(status().isOk());
  }

  @Test void teachersCannotImportAndInvalidCredentialsDoNotRevealAccountExistence() throws Exception {
    users.save(new User("teacher01", passwords.encode("TeacherPassword1"), Role.TEACHER, "教师", null, null, false));
    var teacher = login("teacher01", "TeacherPassword1"); var csrf = csrf(teacher);
    mvc.perform(multipart("/api/admin/imports/execute").file(workbook(Role.TEACHER, new String[][] {{"teacher02", "姓名"}}))
        .param("role", "TEACHER").session(teacher).header(csrf.header(), csrf.token())).andExpect(status().isForbidden());
    String first = null;
    for (String username : new String[] {"missing", "2026001"}) {
      var token = csrf(null);
      var result = mvc.perform(post("/api/auth/login").session(token.session()).header(token.header(), token.token())
          .contentType("application/json").content(json.writeValueAsBytes(Map.of("username", username, "password", "wrong-password"))))
          .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("BAD_CREDENTIALS")).andReturn();
      var error = json.readTree(result.getResponse().getContentAsByteArray()).path("error").toString();
      if (first == null) first = error; else assertEquals(first, error);
    }
  }

  @Test void disablingAccountRevokesSessionAndCsrfIsRequiredForMutations() throws Exception {
    var studentSession = login("2026001", "123456");
    var admin = login("admin", "AdminPassword1");
    mvc.perform(post("/api/admin/users/" + student.getId() + "/active").session(admin)
        .contentType("application/json").content("{\"active\":false}"))
        .andExpect(status().isForbidden());
    var csrf = csrf(admin);
    mvc.perform(post("/api/admin/users/" + student.getId() + "/active").session(admin)
        .header(csrf.header(), csrf.token()).contentType("application/json").content("{\"active\":false}"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/auth/me").session(studentSession)).andExpect(status().isUnauthorized());
  }

  @Test void administratorCannotReadThesesAndCannotCreateOrEditAdministrators() throws Exception {
    var admin = login("admin", "AdminPassword1");
    mvc.perform(get("/api/submissions/7").session(admin)).andExpect(status().isForbidden());
    mvc.perform(get("/api/submissions/7/files/original").session(admin)).andExpect(status().isForbidden());
    var csrf = csrf(admin);
    mvc.perform(post("/api/admin/users").session(admin).header(csrf.header(), csrf.token())
        .contentType("application/json").content("{\"username\":\"admin2\",\"name\":\"新管理员\",\"role\":\"ADMIN\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(patch("/api/admin/users/" + student.getId()).session(admin).header(csrf.header(), csrf.token())
        .contentType("application/json").content("{\"name\":\"新名字\",\"role\":\"TEACHER\"}"))
        .andExpect(status().isBadRequest());
  }

  private MockHttpSession login(String username, String password) throws Exception {
    var csrf = csrf(null);
    return (MockHttpSession) mvc.perform(post("/api/auth/login").session(csrf.session())
        .header(csrf.header(), csrf.token()).contentType("application/json")
        .content(json.writeValueAsBytes(Map.of("username", username, "password", password))))
        .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
  }
  private Csrf csrf(MockHttpSession session) throws Exception {
    var request = get("/api/auth/csrf");
    if (session != null) request.session(session);
    var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
    var data = json.readTree(result.getResponse().getContentAsByteArray()).get("data");
    return new Csrf(data.get("headerName").asText(), data.get("token").asText(),
        (MockHttpSession) result.getRequest().getSession(false));
  }
  private record Csrf(String header, String token, MockHttpSession session) {}
}
