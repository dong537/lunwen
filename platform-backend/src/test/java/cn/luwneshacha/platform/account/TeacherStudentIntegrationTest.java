package cn.luwneshacha.platform.account;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.luwneshacha.platform.auth.PasswordHasher;
import cn.luwneshacha.platform.auth.CurrentUser;
import cn.luwneshacha.platform.common.ApiException;
import cn.luwneshacha.platform.importing.UserImportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(classes = AccountSecurityIntegrationTest.TestApplication.class,
    properties = "platform.bootstrap.enabled=false")
@AutoConfigureMockMvc
class TeacherStudentIntegrationTest {
  @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
  @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
    properties.add("spring.datasource.username", DATABASE::getUsername);
    properties.add("spring.datasource.password", DATABASE::getPassword);
  }
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired UserRepository users;
  @Autowired PasswordHasher passwords;
  @Autowired UserService accounts;
  @Autowired MajorCatalogRepository majors;
  @Autowired UserImportService imports;
  @Autowired JdbcTemplate database;
  User teacher, other;

  @BeforeEach void seed() {
    database.execute("drop trigger if exists disable_roster_teacher on sys_user");
    database.execute("drop function if exists disable_roster_teacher_after_insert()");
    users.findAll().stream().filter(u -> u.getRole() == Role.STUDENT).forEach(users::delete);
    users.deleteAll();
    majors.deleteAll();
    majors.save(new MajorCatalog(College.INFORMATION_ENGINEERING.name(), "计算机"));
    teacher = users.save(new User("teacher01", passwords.encode("Password1"), Role.TEACHER, "教师甲", null, null,
        College.INFORMATION_ENGINEERING.name(), false));
    other = users.save(new User("teacher02", passwords.encode("Password1"), Role.TEACHER, "教师乙", null, null,
        College.INFORMATION_ENGINEERING.name(), false));
  }

  @Test void createsStudentForCurrentTeacherWithoutGroupingOrMandatoryPasswordChange() throws Exception {
    var auth = login("teacher01");
    mvc.perform(post("/api/teacher/students").session(auth.session()).header(auth.header(), auth.token())
        .contentType("application/json").content("{\"username\":\"student01\",\"name\":\" 学生甲 \",\"major\":\"计算机\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.role").value("STUDENT"))
        .andExpect(jsonPath("$.data.teacherId").value(teacher.getId().toString()))
        .andExpect(jsonPath("$.data.name").value("学生甲"))
        .andExpect(jsonPath("$.data.mustChangePassword").value(false));
    var saved = users.findByUsername("student01").orElseThrow();
    assertTrue(passwords.matches("123456", saved.getPasswordHash()));
    mvc.perform(post("/api/teacher/students").session(auth.session()).header(auth.header(), auth.token())
        .contentType("application/json").content("{\"username\":\"injected01\",\"name\":\"学生\",\"teacherId\":\"" + other.getId() + "\"}"))
        .andExpect(status().isBadRequest());
    assertFalse(users.existsByUsername("injected01"));
    for (String field : new String[] {"role", "grade", "className"}) {
      mvc.perform(post("/api/teacher/students").session(auth.session()).header(auth.header(), auth.token())
          .contentType("application/json").content(json.writeValueAsBytes(Map.of(
              "username", "injected02", "name", "学生", field, "不允许的字段"))))
          .andExpect(status().isBadRequest());
    }
    assertFalse(users.existsByUsername("injected02"));
    mvc.perform(post("/api/teacher/students").session(auth.session()).header(auth.header(), auth.token())
        .contentType("application/json").content("{\"username\":\"student01\",\"name\":\"覆盖\"}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("USERNAME_EXISTS"));
    assertEquals("学生甲", users.findByUsername("student01").orElseThrow().getName());
  }

  @Test void validatesStudentFieldsAndDoesNotWriteDuringPreview() throws Exception {
    var auth = login("teacher01");
    for (String body : new String[] {"{\"name\":\"学生\"}", "{\"username\":\"student01\",\"name\":\"\"}"}) {
      mvc.perform(post("/api/teacher/students").session(auth.session()).header(auth.header(), auth.token())
          .contentType("application/json").content(body))
          .andExpect(status().isBadRequest());
    }
    var file = workbook(new String[][] {{"student01", "甲", "计算机"}, {"student02", "", ""}});
    mvc.perform(multipart("/api/teacher/students/imports/preview").file(file)
        .session(auth.session()).header(auth.header(), auth.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.validRows").value(1)).andExpect(jsonPath("$.data.failedCount").value(1))
        .andExpect(jsonPath("$.data.successCount").value(0));
    assertFalse(users.existsByUsername("student01"));
  }

  @Test void importsRosterWithPartialSuccessAndNeverReassignsExistingAccounts() throws Exception {
    var existing = users.save(new User("existing01", passwords.encode("Password1"), Role.STUDENT, "原学生", "原专业", other.getId(), false));
    var auth = login("teacher01");
    var file = workbook(new String[][] {{"student01", "甲", "计算机"}, {"bad001", "", ""},
        {"existing01", "覆盖", "新专业"}, {"dup001", "甲", ""}, {"dup001", "乙", ""}});
    mvc.perform(multipart("/api/teacher/students/imports/execute").file(file)
        .session(auth.session()).header(auth.header(), auth.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalRows").value(5)).andExpect(jsonPath("$.data.successCount").value(1))
        .andExpect(jsonPath("$.data.skippedCount").value(1)).andExpect(jsonPath("$.data.failedCount").value(3));
    var saved = users.findByUsername("student01").orElseThrow();
    assertEquals(teacher.getId(), saved.getTeacherId());
    assertFalse(saved.isMustChangePassword());
    mvc.perform(get("/api/auth/me").session(auth.session())).andExpect(status().isOk());
    assertEquals("原学生", users.findById(existing.getId()).orElseThrow().getName());
    assertEquals(other.getId(), users.findById(existing.getId()).orElseThrow().getTeacherId());
    mvc.perform(multipart("/api/teacher/students/imports/execute").file(file)
        .session(auth.session()).header(auth.header(), auth.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.successCount").value(0)).andExpect(jsonPath("$.data.skippedCount").value(2));
    assertFalse(users.existsByUsername("dup001"));
  }

  @Test void templateHasOnlyThreeTextColumnsAndRejectsTeacherOverrideColumns() throws Exception {
    var auth = login("teacher01");
    byte[] bytes = mvc.perform(get("/api/teacher/students/imports/template").session(auth.session()))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      assertEquals(3, book.getSheetAt(0).getRow(0).getLastCellNum());
      assertEquals("专业", book.getSheetAt(0).getRow(0).getCell(2).getStringCellValue());
      assertEquals("@", book.getSheetAt(0).getColumnStyle(0).getDataFormatString());
    }
    mvc.perform(multipart("/api/teacher/students/imports/execute")
        .file(workbook(new String[][] {{"student01", "学生", "", "teacher02"}}))
        .session(auth.session()).header(auth.header(), auth.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.failedCount").value(1));
    assertFalse(users.existsByUsername("student01"));
  }

  @Test void executionRechecksTeacherAfterPreview() throws Exception {
    var auth = login("teacher01");
    var file = workbook(new String[][] {{"student01", "学生", ""}});
    mvc.perform(multipart("/api/teacher/students/imports/preview").file(file)
        .session(auth.session()).header(auth.header(), auth.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.validRows").value(1));
    teacher.setActive(false); users.saveAndFlush(teacher);
    mvc.perform(multipart("/api/teacher/students/imports/execute").file(file)
        .session(auth.session()).header(auth.header(), auth.token())).andExpect(status().isUnauthorized());
    assertFalse(users.existsByUsername("student01"));
  }

  @Test void concurrentRosterImportsCreateOneAccountWithoutChangingItsTeacher() throws Exception {
    var file = workbook(new String[][] {{"shared001", "学生", ""}});
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> {
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return imports.processTeacherStudents(CurrentUser.from(teacher), file, true);
      });
      var second = executor.submit(() -> {
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return imports.processTeacherStudents(CurrentUser.from(other), file, true);
      });
      start.countDown();
      var a = first.get(20, TimeUnit.SECONDS);
      var b = second.get(20, TimeUnit.SECONDS);
      assertEquals(1, a.successCount() + b.successCount());
      assertEquals(1, a.skippedCount() + b.skippedCount());
      assertEquals(0, a.failedCount() + b.failedCount());
      assertEquals(a.successCount() == 1 ? teacher.getId() : other.getId(),
          users.findByUsername("shared001").orElseThrow().getTeacherId());
    }
  }

  @Test void serviceRechecksTeacherStatusRatherThanTrustingAnEarlierPrincipal() {
    var principal = CurrentUser.from(teacher);
    teacher.setActive(false); users.saveAndFlush(teacher);
    var error = assertThrows(ApiException.class, () -> accounts.createStudent(principal,
        new UserService.CreateStudent("student01", "学生", null)));
    assertEquals(403, error.status().value());
    assertFalse(users.existsByUsername("student01"));
  }

  @Test void importStopsAfterTeacherIsRevokedMidRequestAndKeepsEarlierCommittedRows() throws Exception {
    var auth = login("teacher01");
    database.execute("""
        create function disable_roster_teacher_after_insert() returns trigger language plpgsql as $$
        begin
          update sys_user set is_active=false where username='teacher01';
          return new;
        end $$
        """);
    database.execute("""
        create trigger disable_roster_teacher after insert on sys_user
        for each row when (new.role='STUDENT') execute function disable_roster_teacher_after_insert()
        """);
    mvc.perform(multipart("/api/teacher/students/imports/execute")
        .file(workbook(new String[][] {{"student01", "甲", ""}, {"student02", "乙", ""}}))
        .session(auth.session()).header(auth.header(), auth.token())).andExpect(status().isForbidden());
    assertTrue(users.existsByUsername("student01"));
    assertFalse(users.existsByUsername("student02"));
    assertFalse(users.findByUsername("teacher01").orElseThrow().isActive());
  }

  @Test void requiresTeacherSessionCsrfAndPasswordChange() throws Exception {
    var auth = login("teacher01");
    mvc.perform(post("/api/teacher/students").session(auth.session()).contentType("application/json")
        .content("{\"username\":\"student01\",\"name\":\"学生\"}"))
        .andExpect(status().isForbidden());
    users.save(new User("student01", passwords.encode("Password1"), Role.STUDENT, "学生", null, null, false));
    var student = login("student01");
    mvc.perform(get("/api/teacher/students/imports/template").session(student.session())).andExpect(status().isForbidden());
    mvc.perform(get("/api/teacher/students/imports/template")).andExpect(status().isUnauthorized());
    teacher.changePassword(passwords.encode("Password1"), true); users.saveAndFlush(teacher);
    var initial = login("teacher01");
    mvc.perform(get("/api/teacher/students/imports/template").session(initial.session())).andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("PASSWORD_CHANGE_REQUIRED"));
  }

  private Auth login(String username) throws Exception {
    var csrfResult = mvc.perform(get("/api/auth/csrf")).andReturn();
    var session = (MockHttpSession) csrfResult.getRequest().getSession();
    var data = json.readTree(csrfResult.getResponse().getContentAsByteArray()).path("data");
    String header = data.path("headerName").asText(), token = data.path("token").asText();
    var result = mvc.perform(post("/api/auth/login").session(session).header(header, token).contentType("application/json")
        .content(json.writeValueAsBytes(Map.of("username", username, "password", "Password1"))))
        .andExpect(status().isOk()).andReturn();
    session = (MockHttpSession) result.getRequest().getSession();
    data = json.readTree(mvc.perform(get("/api/auth/csrf").session(session)).andReturn().getResponse().getContentAsByteArray()).path("data");
    return new Auth(session, data.path("headerName").asText(), data.path("token").asText());
  }
  private MockMultipartFile workbook(String[][] rows) throws Exception {
    try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
      var sheet = book.createSheet("学生账号"); var header = sheet.createRow(0);
      String[] columns = {"学号", "姓名", "专业"};
      for (int i = 0; i < columns.length; i++) header.createCell(i).setCellValue(columns[i]);
      for (int i = 0; i < rows.length; i++) {
        var row = sheet.createRow(i + 1);
        for (int j = 0; j < rows[i].length; j++) row.createCell(j).setCellValue(rows[i][j]);
      }
      book.write(output);
      return new MockMultipartFile("file", "students.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
    }
  }
  record Auth(MockHttpSession session, String header, String token) {}
}
