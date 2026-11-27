package cn.luwneshacha.platform.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

import cn.luwneshacha.platform.auth.PasswordHasher;
import cn.luwneshacha.platform.auth.CurrentUser;
import cn.luwneshacha.platform.submission.SubmissionRepository;
import cn.luwneshacha.platform.submission.SubmissionController;
import cn.luwneshacha.platform.submission.SubmissionService;
import cn.luwneshacha.platform.submission.TransferProperties;
import cn.luwneshacha.platform.storage.ObjectPresigner;
import cn.luwneshacha.platform.storage.ObjectStorage;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
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
  @ComponentScan(basePackages = {"cn.luwneshacha.platform.account", "cn.luwneshacha.platform.auth",
      "cn.luwneshacha.platform.common", "cn.luwneshacha.platform.importing"},
      excludeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION,
          classes = {SpringBootConfiguration.class, TestConfiguration.class}))
  @EntityScan({"cn.luwneshacha.platform.account", "cn.luwneshacha.platform.submission"})
  @EnableJpaRepositories({"cn.luwneshacha.platform.account", "cn.luwneshacha.platform.submission"})
  static class TestApplication {
    @Bean SubmissionService submissionService(SubmissionRepository submissions, UserRepository users,
        org.springframework.transaction.PlatformTransactionManager transactions) {
      return new SubmissionService(submissions, users, org.mockito.Mockito.mock(ObjectStorage.class),
          transactions, org.mockito.Mockito.mock(ObjectPresigner.class),
          new TransferProperties(TransferProperties.Mode.DIRECT, 600));
    }
    @Bean SubmissionController submissionController(SubmissionService service) {
      return new SubmissionController(service);
    }
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired UserRepository users;
  @Autowired PasswordHasher passwords;
  @Autowired UserService accounts;
  @Autowired MajorCatalogRepository majors;
  @Autowired SubmissionRepository submissions;
  @Autowired JdbcTemplate database;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  User student;

  @BeforeEach void seed() {
    submissions.deleteAll();
    users.findAll().stream().filter(u -> u.getRole() == Role.STUDENT).forEach(users::delete);
    users.deleteAll();
    majors.deleteAll();
    majors.save(new MajorCatalog(College.INFORMATION_ENGINEERING.name(), "计算机"));
    majors.save(new MajorCatalog(College.INFORMATION_ENGINEERING.name(), "计算机科学"));
    users.save(new User("admin", passwords.encode("AdminPassword1"), Role.ADMIN, "管理员", null, null, false));
    student = users.save(new User("2026001", passwords.encode("123456"), Role.STUDENT, "测试学生", null, null,
        College.INFORMATION_ENGINEERING.name(), true));
  }

  @Test void loginRequiresMatchingPersistedCollegeWithoutCreatingRejectedSession() throws Exception {
    var token = csrf(null);
    for (var selection : new String[]{"", College.EDUCATION_ARTS.name()}) {
      mvc.perform(post("/api/auth/login").session(token.session()).header(token.header(), token.token())
          .contentType("application/json").content(json.writeValueAsBytes(Map.of(
              "username", student.getUsername(), "password", "123456", "collegeCode", selection))))
          .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code")
              .value(selection.isEmpty() ? "COLLEGE_REQUIRED" : "LOGIN_COLLEGE_MISMATCH"));
      mvc.perform(get("/api/auth/me").session(token.session())).andExpect(status().isUnauthorized());
    }
    mvc.perform(post("/api/auth/login").session(token.session()).header(token.header(), token.token())
        .contentType("application/json").content(json.writeValueAsBytes(Map.of(
            "username", student.getUsername(), "password", "123456", "collegeCode", College.INFORMATION_ENGINEERING.name()))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("STUDENT"))
        .andExpect(jsonPath("$.data.collegeCode").value(College.INFORMATION_ENGINEERING.name()));
    assertEquals(College.INFORMATION_ENGINEERING.name(), users.findById(student.getId()).orElseThrow().getCollegeCode());
  }

  @Test void rejectedCollegeSelectionCannotReplaceAnExistingSchoolAdminSession() throws Exception {
    var admin = login("admin", "AdminPassword1");
    var token = csrf(admin);
    mvc.perform(post("/api/auth/login").session(admin).header(token.header(), token.token())
        .contentType("application/json").content(json.writeValueAsBytes(Map.of(
            "username", student.getUsername(), "password", "123456", "collegeCode", College.EDUCATION_ARTS.name()))))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("LOGIN_COLLEGE_MISMATCH"));
    mvc.perform(get("/api/auth/me").session(admin)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value("admin"))
        .andExpect(jsonPath("$.data.role").value("ADMIN")).andExpect(jsonPath("$.data.collegeCode").isEmpty());
  }

  @Test void databaseAcceptsEveryPublishedCollegeForAccountsAndMajorCatalog() {
    String hash = passwords.encode("CatalogPassword42");
    int sequence = 0;
    for (var college : College.values()) {
      var account = users.saveAndFlush(new User("catalog_" + sequence++, hash, Role.TEACHER, "目录验收教师",
          null, null, college.name(), false));
      var major = majors.saveAndFlush(new MajorCatalog(college.name(), "目录验收专业"));
      assertEquals(college.name(), users.findById(account.getId()).orElseThrow().getCollegeCode());
      assertEquals(college.name(), majors.findById(major.getId()).orElseThrow().getCollegeCode());
    }
  }

  @Test void importPreviewDoesNotWriteAndExecutionImportsOnlyValidNewAccounts() throws Exception {
    var teacher = users.save(new User("teacher01", passwords.encode("123456"), Role.TEACHER, "指导老师", null, null, College.INFORMATION_ENGINEERING.name(), true));
    var file = workbook(Role.STUDENT, new String[][] {{"new001", "新学生", "计算机", "teacher01"},
        {"2026001", "不可覆盖的名字", "新专业", "teacher01"}, {"bad001", "", "", ""},
        {"bad002", "学生", "", "missing"}});
    var admin = login("admin", "AdminPassword1");
    var csrf = csrf(admin);
    mvc.perform(multipart("/api/admin/imports/preview").file(file).param("role", "STUDENT")
        .param("collegeCode", College.INFORMATION_ENGINEERING.name())
        .session(admin).header(csrf.header(), csrf.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalRows").value(4)).andExpect(jsonPath("$.data.validRows").value(1))
        .andExpect(jsonPath("$.data.successCount").value(0)).andExpect(jsonPath("$.data.skippedCount").value(1))
        .andExpect(jsonPath("$.data.failedCount").value(2));
    assertFalse(users.existsByUsername("new001"));
    mvc.perform(multipart("/api/admin/imports/execute").file(file).param("role", "STUDENT")
        .param("collegeCode", College.INFORMATION_ENGINEERING.name())
        .session(admin).header(csrf.header(), csrf.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.successCount").value(1)).andExpect(jsonPath("$.data.failedCount").value(2));
    var imported = users.findByUsername("new001").orElseThrow();
    assertEquals(teacher.getId(), imported.getTeacherId());
    assertEquals(2023, imported.getGrade());
    assertFalse(imported.isMustChangePassword());
    assertTrue(passwords.matches("123456", imported.getPasswordHash()));
    assertEquals("测试学生", users.findByUsername("2026001").orElseThrow().getName());
    mvc.perform(multipart("/api/admin/imports/execute").file(file).param("role", "STUDENT")
        .param("collegeCode", College.INFORMATION_ENGINEERING.name())
        .session(admin).header(csrf.header(), csrf.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.successCount").value(0)).andExpect(jsonPath("$.data.skippedCount").value(2));
  }

  @Test void importsOneHundredStudentsAndRepeatingTheFileSkipsEveryAccount() throws Exception {
    var teacher = users.save(new User("teacher100", passwords.encode("123456"), Role.TEACHER, "批量导入导师", null, null, College.INFORMATION_ENGINEERING.name(), true));
    var rows = java.util.stream.IntStream.range(0, 100)
        .mapToObj(i -> new String[] {"202610" + String.format(java.util.Locale.ROOT, "%03d", i), "导入测试学生" + i, "计算机科学", "teacher100"})
        .toArray(String[][]::new);
    var file = workbook(Role.STUDENT, rows);
    var admin = login("admin", "AdminPassword1"); var csrf = csrf(admin);
    mvc.perform(multipart("/api/admin/imports/execute").file(file).param("role", "STUDENT")
        .param("collegeCode", College.INFORMATION_ENGINEERING.name())
        .session(admin).header(csrf.header(), csrf.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalRows").value(100)).andExpect(jsonPath("$.data.successCount").value(100))
        .andExpect(jsonPath("$.data.skippedCount").value(0)).andExpect(jsonPath("$.data.failedCount").value(0));
    var imported = users.findAll().stream().filter(u -> u.getUsername().startsWith("202610")).toList();
    assertEquals(100, imported.size());
    assertTrue(imported.stream().allMatch(u -> u.getTeacherId().equals(teacher.getId()) && !u.isMustChangePassword() && u.isActive()));
    var identities = imported.stream().collect(java.util.stream.Collectors.toMap(User::getUsername, User::getId));
    mvc.perform(multipart("/api/admin/imports/execute").file(file).param("role", "STUDENT")
        .param("collegeCode", College.INFORMATION_ENGINEERING.name())
        .session(admin).header(csrf.header(), csrf.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalRows").value(100)).andExpect(jsonPath("$.data.successCount").value(0))
        .andExpect(jsonPath("$.data.skippedCount").value(100)).andExpect(jsonPath("$.data.failedCount").value(0));
    assertEquals(identities, users.findAll().stream().filter(u -> u.getUsername().startsWith("202610"))
        .collect(java.util.stream.Collectors.toMap(User::getUsername, User::getId)));
  }

  @Test void importRejectsAllDuplicateRowsAndRechecksTeacherOnExecution() throws Exception {
    var teacher = users.save(new User("teacher01", passwords.encode("123456"), Role.TEACHER, "教师", null, null, College.INFORMATION_ENGINEERING.name(), false));
    var file = workbook(Role.STUDENT, new String[][] {{"dup001", "甲", "", ""}, {"dup001", "乙", "", ""},
        {"new002", "丙", "", "teacher01"}});
    var admin = login("admin", "AdminPassword1");
    var csrf = csrf(admin);
    mvc.perform(multipart("/api/admin/imports/preview").file(file).param("role", "STUDENT")
        .param("collegeCode", College.INFORMATION_ENGINEERING.name())
        .session(admin).header(csrf.header(), csrf.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.validRows").value(1)).andExpect(jsonPath("$.data.failedCount").value(2));
    teacher.setActive(false); users.saveAndFlush(teacher);
    mvc.perform(multipart("/api/admin/imports/execute").file(file).param("role", "STUDENT")
        .param("collegeCode", College.INFORMATION_ENGINEERING.name())
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
      assertEquals("指导教师工号", book.getSheetAt(0).getRow(0).getCell(6).getStringCellValue());
      assertEquals("@", book.getSheetAt(0).getColumnStyle(0).getDataFormatString());
    }
    String[][] tooMany = java.util.stream.IntStream.range(0, 2001).mapToObj(i -> new String[] {"user" + i, "姓名"}).toArray(String[][]::new);
    mvc.perform(multipart("/api/admin/imports/preview").file(workbook(Role.TEACHER, tooMany)).param("role", "TEACHER")
        .param("collegeCode", College.INFORMATION_ENGINEERING.name())
        .session(admin).header(csrf.header(), csrf.token())).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("IMPORT_ROW_LIMIT"));
    mvc.perform(multipart("/api/admin/imports/preview").file(new MockMultipartFile("file", "users.xlsx", "application/octet-stream", new byte[5 * 1024 * 1024 + 1]))
        .param("role", "TEACHER").param("collegeCode", College.INFORMATION_ENGINEERING.name())
        .session(admin).header(csrf.header(), csrf.token())).andExpect(status().isPayloadTooLarge());
    mvc.perform(multipart("/api/admin/imports/preview").file(new MockMultipartFile("file", "users.csv", "text/csv", new byte[] {1}))
        .param("role", "TEACHER").param("collegeCode", College.INFORMATION_ENGINEERING.name())
        .session(admin).header(csrf.header(), csrf.token())).andExpect(status().isBadRequest());
    mvc.perform(get("/api/admin/imports/template").param("role", "ADMIN").session(admin)).andExpect(status().isBadRequest());
  }

  @Test void schoolAdministratorImportsUsingVerifiedExcelCollegeWithoutPageScope() throws Exception {
    String username = "missing_college_teacher";
    var file = workbook(Role.TEACHER, new String[][] {{username, "待导入教师"}});
    var admin = login("admin", "AdminPassword1"); var csrf = csrf(admin);
    long accountCount = users.count();
    assertFalse(users.existsByUsername(username));
    mvc.perform(multipart("/api/admin/imports/preview").file(file).param("role", "TEACHER")
        .param("collegeCode", College.INFORMATION_ENGINEERING.name())
        .session(admin).header(csrf.header(), csrf.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.validRows").value(1));
    mvc.perform(multipart("/api/admin/imports/preview").file(file).param("role", "TEACHER")
        .session(admin).header(csrf.header(), csrf.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.validRows").value(1));
    assertEquals(accountCount, users.count());
    mvc.perform(multipart("/api/admin/imports/execute").file(file).param("role", "TEACHER")
        .session(admin).header(csrf.header(), csrf.token())).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.successCount").value(1));
    assertEquals(College.INFORMATION_ENGINEERING.name(), users.findByUsername(username).orElseThrow().getCollegeCode());
  }

  @Test void gradeFilteringKeepsUnknownLegacyStudentsDistinctFromExplicitYears() throws Exception {
    var admin = login("admin", "AdminPassword1"); var csrf = csrf(admin);
    for (int year : new int[] {2023, 2024}) {
      mvc.perform(post("/api/admin/users").session(admin).header(csrf.header(), csrf.token())
          .contentType("application/json").content(json.writeValueAsBytes(Map.of("username", "new_" + year,
              "name", "学生" + year, "role", "STUDENT", "grade", year,
              "collegeCode", College.INFORMATION_ENGINEERING.name()))))
          .andExpect(status().isOk()).andExpect(jsonPath("$.data.grade").value(year));
    }
    mvc.perform(get("/api/admin/users").session(admin).param("role", "STUDENT"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3));
    mvc.perform(get("/api/admin/users").session(admin).param("role", "STUDENT").param("grade", "2023"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.items[0].username").value("new_2023"));
    var yearStudent = users.findByUsername("new_2023").orElseThrow();
    mvc.perform(patch("/api/admin/users/" + yearStudent.getId()).session(admin)
            .header(csrf.header(), csrf.token()).contentType("application/json")
            .content(json.writeValueAsBytes(Map.of("name", "学生改名", "collegeCode", College.INFORMATION_ENGINEERING.name()))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.grade").value(2023));
    assertEquals(2023, users.findByUsername("new_2023").orElseThrow().getGrade());
    mvc.perform(get("/api/admin/users").session(admin).param("role", "STUDENT").param("grade", "UNSET"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.items[0].username").value("2026001"))
        .andExpect(jsonPath("$.data.items[0].grade").isEmpty());
    mvc.perform(get("/api/admin/users").session(admin).param("role", "TEACHER").param("grade", "2023"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_GRADE"));
    mvc.perform(get("/api/admin/users").session(admin).param("role", "STUDENT").param("grade", "1999"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_GRADE"));
    mvc.perform(post("/api/admin/users").session(admin).header(csrf.header(), csrf.token())
        .contentType("application/json").content(json.writeValueAsBytes(Map.of("username", "missing_grade",
            "name", "学生", "role", "STUDENT", "collegeCode", College.INFORMATION_ENGINEERING.name()))))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("GRADE_REQUIRED"));
    assertFalse(users.existsByUsername("missing_grade"));
  }

  @Test void teacherChoicesArePagedAndSearchableBeyondOneHundredAccounts() throws Exception {
    for (int i = 1; i <= 101; i++) users.save(new User("choice_" + String.format("%03d", i),
        passwords.encode("Password1"), Role.TEACHER, "教师" + i, null, null,
        College.INFORMATION_ENGINEERING.name(), false));
    var admin = login("admin", "AdminPassword1");
    mvc.perform(get("/api/admin/teachers").session(admin).param("collegeCode", College.INFORMATION_ENGINEERING.name())
        .param("page", "6").param("size", "20"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(101))
        .andExpect(jsonPath("$.data.items.length()").value(1))
        .andExpect(jsonPath("$.data.items[0].username").value("choice_101"));
    mvc.perform(get("/api/admin/teachers").session(admin).param("collegeCode", College.INFORMATION_ENGINEERING.name())
        .param("q", "choice_101"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
  }

  @Test void excelSchoolCollegeAndMajorAreValidatedPerRowWithoutGrantingScope() throws Exception {
    majors.save(new MajorCatalog(College.EDUCATION_ARTS.name(), "学前教育"));
    var file = workbook(Role.TEACHER, new String[][] {
        {"row_info", "信息教师", College.SCHOOL_NAME, College.INFORMATION_ENGINEERING.displayName(), "计算机"},
        {"row_arts", "艺术教师", College.SCHOOL_NAME, College.EDUCATION_ARTS.displayName(), "学前教育"},
        {"row_wrong", "错误学校", "另一所学校", College.INFORMATION_ENGINEERING.displayName(), "计算机"}});
    var admin = login("admin", "AdminPassword1"); var csrf = csrf(admin);
    mvc.perform(multipart("/api/admin/imports/preview").file(file).param("role", "TEACHER")
        .session(admin).header(csrf.header(), csrf.token()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.validRows").value(2))
        .andExpect(jsonPath("$.data.failedCount").value(1));
    mvc.perform(multipart("/api/admin/imports/execute").file(file).param("role", "TEACHER")
        .session(admin).header(csrf.header(), csrf.token()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.successCount").value(2));
    assertEquals("计算机", users.findByUsername("row_info").orElseThrow().getMajor());
    assertEquals(College.EDUCATION_ARTS.name(), users.findByUsername("row_arts").orElseThrow().getCollegeCode());
    users.save(new User("college_admin", passwords.encode("Password1"), Role.ADMIN, "院管", null, null,
        College.INFORMATION_ENGINEERING.name(), false));
    var collegeAdmin = login("college_admin", "Password1"); var collegeCsrf = csrf(collegeAdmin);
    mvc.perform(multipart("/api/admin/imports/preview").file(file).param("role", "TEACHER")
        .session(collegeAdmin).header(collegeCsrf.header(), collegeCsrf.token()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.skippedCount").value(1))
        .andExpect(jsonPath("$.data.failedCount").value(2));
  }

  @Test void teacherManagementWarnsAboutAssignedStudentsAndKeepsAdministratorImmutable() throws Exception {
    var admin = login("admin", "AdminPassword1"); var csrf = csrf(admin);
    var created = mvc.perform(post("/api/admin/users").session(admin).header(csrf.header(), csrf.token())
        .contentType("application/json").content("{\"username\":\"teacher02\",\"name\":\"教师\",\"role\":\"TEACHER\",\"collegeCode\":\"INFORMATION_ENGINEERING\"}"))
        .andExpect(status().isOk()).andReturn();
    String teacherId = json.readTree(created.getResponse().getContentAsByteArray()).path("data").path("id").asText();
    assertTrue(users.findById(Long.parseLong(teacherId)).orElseThrow().isMustChangePassword());
    mvc.perform(patch("/api/admin/users/" + student.getId()).session(admin).header(csrf.header(), csrf.token())
        .contentType("application/json").content(json.writeValueAsBytes(Map.of("name", "学生", "teacherId", teacherId,
            "collegeCode", College.INFORMATION_ENGINEERING.name()))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.teacherName").value("教师"));
    mvc.perform(post("/api/admin/users/" + teacherId + "/active").session(admin).header(csrf.header(), csrf.token())
        .contentType("application/json").content("{\"active\":false}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.assignedStudents").value(1))
        .andExpect(jsonPath("$.data.message").isNotEmpty());
    long adminId = users.findByUsername("admin").orElseThrow().getId();
    mvc.perform(post("/api/admin/users/" + adminId + "/reset-password").session(admin).header(csrf.header(), csrf.token()))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/users").session(admin).param("role", "STUDENT").param("page", "0"))
        .andExpect(status().isBadRequest());
  }

  @Test void collegeAdministratorIsRestrictedAcrossListsMutationsAndTeacherChoices() throws Exception {
    users.save(new User("info_teacher", passwords.encode("Password1"), Role.TEACHER, "信息学院教师", null, null,
        College.INFORMATION_ENGINEERING.name(), false));
    User otherTeacher = users.save(new User("arts_teacher", passwords.encode("Password1"), Role.TEACHER, "艺术学院教师", null, null,
        College.EDUCATION_ARTS.name(), false));
    users.save(new User("college_admin", passwords.encode("Password1"), Role.ADMIN, "学院管理员", null, null,
        College.INFORMATION_ENGINEERING.name(), false));
    var session = login("college_admin", "Password1"); var csrf = csrf(session);
    mvc.perform(get("/api/admin/catalog").session(session)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.schoolName").value(College.SCHOOL_NAME))
        .andExpect(jsonPath("$.data.colleges.length()").value(1));
    mvc.perform(get("/api/admin/users").session(session).param("role", "TEACHER"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.items[0].username").value("info_teacher"));
    mvc.perform(get("/api/admin/users").session(session).param("role", "ADMIN"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/teachers").session(session).param("collegeCode", College.INFORMATION_ENGINEERING.name()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2));
    mvc.perform(get("/api/admin/teachers").session(session).param("collegeCode", College.EDUCATION_ARTS.name()))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/majors").session(session).param("collegeCode", College.EDUCATION_ARTS.name()))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/admin/users").session(session).header(csrf.header(), csrf.token())
        .contentType("application/json").content(json.writeValueAsBytes(Map.of("username", "cross_teacher",
            "name", "教师", "role", "TEACHER", "collegeCode", College.EDUCATION_ARTS.name()))))
        .andExpect(status().isForbidden());
    mvc.perform(delete("/api/admin/users/" + otherTeacher.getId()).session(session).header(csrf.header(), csrf.token()))
        .andExpect(status().isForbidden());
    assertTrue(users.findById(otherTeacher.getId()).orElseThrow().isActive());
  }

  @Test void schoolAdminMaintainsCollegeAdministratorsAndMajorsWithoutErasingLegacyMajor() throws Exception {
    var session = login("admin", "AdminPassword1"); var csrf = csrf(session);
    mvc.perform(get("/api/admin/catalog").session(session)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.colleges.length()").value(College.values().length));
    mvc.perform(post("/api/admin/users").session(session).header(csrf.header(), csrf.token())
        .contentType("application/json").content(json.writeValueAsBytes(Map.of("username", "arts_admin",
            "name", "艺术学院管理员", "role", "ADMIN", "collegeCode", College.EDUCATION_ARTS.name()))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.collegeName").value("教育与艺术学院"));
    mvc.perform(get("/api/admin/users").session(session).param("role", "ADMIN"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2));
    var major = mvc.perform(post("/api/admin/majors").session(session).header(csrf.header(), csrf.token())
        .contentType("application/json").content(json.writeValueAsBytes(Map.of("collegeCode", College.INFORMATION_ENGINEERING.name(),
            "name", "电子信息")))).andExpect(status().isCreated()).andReturn();
    String majorId = json.readTree(major.getResponse().getContentAsByteArray()).path("data").path("id").asText();
    mvc.perform(patch("/api/admin/majors/" + majorId + "/active").session(session).header(csrf.header(), csrf.token())
        .contentType("application/json").content("{\"active\":false}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(false));
    mvc.perform(post("/api/admin/users").session(session).header(csrf.header(), csrf.token())
        .contentType("application/json").content(json.writeValueAsBytes(Map.of("username", "new_student",
            "name", "学生", "role", "STUDENT", "major", "电子信息", "grade", 2023,
            "collegeCode", College.INFORMATION_ENGINEERING.name()))))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_MAJOR"));
    student.updateProfile(student.getName(), "历史专业", null); users.saveAndFlush(student);
    mvc.perform(patch("/api/admin/users/" + student.getId()).session(session).header(csrf.header(), csrf.token())
        .contentType("application/json").content(json.writeValueAsBytes(Map.of("name", "新名字",
            "major", "历史专业", "collegeCode", College.INFORMATION_ENGINEERING.name()))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.major").value("历史专业"));
  }

  @Test void deleteRemovesUnreferencedAccountsButDeactivatesAccountsWithHistory() throws Exception {
    var session = login("admin", "AdminPassword1"); var csrf = csrf(session);
    User unused = users.save(new User("unused_teacher", passwords.encode("Password1"), Role.TEACHER, "未使用", null, null,
        College.INFORMATION_ENGINEERING.name(), false));
    mvc.perform(delete("/api/admin/users/" + unused.getId()).session(session).header(csrf.header(), csrf.token()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("DELETED"));
    assertFalse(users.existsById(unused.getId()));
    User teacher = users.save(new User("assigned_teacher", passwords.encode("Password1"), Role.TEACHER, "有学生", null, null,
        College.INFORMATION_ENGINEERING.name(), false));
    student.updateProfile(student.getName(), student.getMajor(), teacher.getId()); users.saveAndFlush(student);
    mvc.perform(delete("/api/admin/users/" + teacher.getId()).session(session).header(csrf.header(), csrf.token()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("DEACTIVATED"));
    assertFalse(users.findById(teacher.getId()).orElseThrow().isActive());
    database.update("insert into submission(student_id,original_filename,file_size,status,storage_dir,created_at,updated_at) values (?, 'x.docx', 1, 'SUCCESS', ?, now(), now())",
        student.getId(), "delete-check-" + student.getId());
    mvc.perform(delete("/api/admin/users/" + student.getId()).session(session).header(csrf.header(), csrf.token()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("DEACTIVATED"));
    assertFalse(users.findById(student.getId()).orElseThrow().isActive());
    long rootAdminId = users.findByUsername("admin").orElseThrow().getId();
    mvc.perform(delete("/api/admin/users/" + rootAdminId).session(session).header(csrf.header(), csrf.token()))
        .andExpect(status().isForbidden());
  }

  @Test void deleteStudentWithTeacherButNoSubmissionsPreservesTeacher() throws Exception {
    User teacher = users.save(new User("unused_student_teacher", passwords.encode("Password1"), Role.TEACHER,
        "指导教师", null, null, College.INFORMATION_ENGINEERING.name(), false));
    student.updateProfile(student.getName(), student.getMajor(), teacher.getId());
    users.saveAndFlush(student);
    var session = login("admin", "AdminPassword1"); var csrf = csrf(session);
    mvc.perform(delete("/api/admin/users/" + student.getId()).session(session).header(csrf.header(), csrf.token()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("DELETED"))
        .andExpect(jsonPath("$.data.user").isEmpty());
    assertFalse(users.existsById(student.getId()));
    assertTrue(users.findById(teacher.getId()).orElseThrow().isActive());
    assertEquals(0, submissions.count());
  }

  @Test void studentCannotSelectAnotherCollegeTeacherAndTeacherWithStudentsCannotMove() throws Exception {
    User artsTeacher = users.save(new User("arts_teacher", passwords.encode("Password1"), Role.TEACHER, "艺术教师",
        null, null, College.EDUCATION_ARTS.name(), false));
    var session = login("admin", "AdminPassword1"); var csrf = csrf(session);
    mvc.perform(post("/api/admin/users").session(session).header(csrf.header(), csrf.token())
        .contentType("application/json").content(json.writeValueAsBytes(Map.of("username", "cross_student",
            "name", "跨院学生", "role", "STUDENT", "teacherId", artsTeacher.getId().toString(),
            "grade", 2023, "collegeCode", College.INFORMATION_ENGINEERING.name()))))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_TEACHER"));
    User infoTeacher = users.save(new User("info_teacher", passwords.encode("Password1"), Role.TEACHER, "信息教师",
        null, null, College.INFORMATION_ENGINEERING.name(), false));
    student.updateProfile(student.getName(), student.getMajor(), infoTeacher.getId()); users.saveAndFlush(student);
    mvc.perform(patch("/api/admin/users/" + infoTeacher.getId()).session(session).header(csrf.header(), csrf.token())
        .contentType("application/json").content(json.writeValueAsBytes(Map.of("name", "信息教师",
            "collegeCode", College.EDUCATION_ARTS.name()))))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TEACHER_HAS_STUDENTS"));
    student.updateProfile(student.getName(), "计算机", null); users.saveAndFlush(student);
    mvc.perform(patch("/api/admin/users/" + student.getId()).session(session).header(csrf.header(), csrf.token())
        .contentType("application/json").content(json.writeValueAsBytes(Map.of("name", "学生",
            "major", "计算机", "collegeCode", College.EDUCATION_ARTS.name()))))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_MAJOR"));
  }

  @Test void backfillLegacyTeacherAndStudentCollegeWithoutGuessingStudentCollege() throws Exception {
    User legacyTeacher = users.save(new User("legacy_teacher", passwords.encode("Password1"), Role.TEACHER,
        "待归属教师", null, null, false));
    User legacyStudent = users.save(new User("legacy_student", passwords.encode("Password1"), Role.STUDENT,
        "待归属学生", "旧专业", legacyTeacher.getId(), false));
    var session = login("admin", "AdminPassword1"); var csrf = csrf(session);
    mvc.perform(patch("/api/admin/users/" + legacyTeacher.getId()).session(session).header(csrf.header(), csrf.token())
        .contentType("application/json").content(json.writeValueAsBytes(Map.of("name", "待归属教师",
            "collegeCode", College.INFORMATION_ENGINEERING.name()))))
        .andExpect(status().isOk());
    assertNull(users.findById(legacyStudent.getId()).orElseThrow().getCollegeCode());
    mvc.perform(patch("/api/admin/users/" + legacyStudent.getId()).session(session).header(csrf.header(), csrf.token())
        .contentType("application/json").content(json.writeValueAsBytes(Map.of("name", "待归属学生",
            "major", "旧专业", "teacherId", legacyTeacher.getId().toString(),
            "collegeCode", College.INFORMATION_ENGINEERING.name()))))
        .andExpect(status().isOk());
    assertEquals(College.INFORMATION_ENGINEERING.name(), users.findById(legacyStudent.getId()).orElseThrow().getCollegeCode());
  }

  @Test void existingSessionUsesCurrentDatabaseCollegeAndStopsWhenDisabled() throws Exception {
    User manager = users.save(new User("scope_admin", passwords.encode("Password1"), Role.ADMIN, "学院管理员", null, null,
        College.INFORMATION_ENGINEERING.name(), false));
    users.save(new User("arts_teacher", passwords.encode("Password1"), Role.TEACHER, "艺术教师", null, null,
        College.EDUCATION_ARTS.name(), false));
    var managerSession = login("scope_admin", "Password1");
    mvc.perform(get("/api/admin/users").session(managerSession).param("role", "TEACHER"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    var schoolSession = login("admin", "AdminPassword1"); var csrf = csrf(schoolSession);
    mvc.perform(patch("/api/admin/users/" + manager.getId()).session(schoolSession).header(csrf.header(), csrf.token())
        .contentType("application/json").content(json.writeValueAsBytes(Map.of("name", "学院管理员",
            "collegeCode", College.EDUCATION_ARTS.name()))))
        .andExpect(status().isOk());
    mvc.perform(get("/api/admin/users").session(managerSession).param("role", "TEACHER"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.items[0].username").value("arts_teacher"));
    mvc.perform(post("/api/admin/users/" + manager.getId() + "/active").session(schoolSession)
        .header(csrf.header(), csrf.token()).contentType("application/json").content("{\"active\":false}"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/admin/users").session(managerSession).param("role", "TEACHER"))
        .andExpect(status().isUnauthorized());
  }

  @Test void concurrentProfileAndPasswordResetPreserveBothChanges() throws Exception {
    var updated = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var resetting = new java.util.concurrent.CountDownLatch(1);
    String initialHash = student.getPasswordHash();
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var profile = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> {
        accounts.update(CurrentUser.from(users.findByUsername("admin").orElseThrow()), student.getId(),
            new UserService.UpdateUser("并发修改后的名字", "计算机", null, College.INFORMATION_ENGINEERING.name(), null));
        updated.countDown();
        try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        return null;
      }));
      assertTrue(updated.await(5, java.util.concurrent.TimeUnit.SECONDS));
      var reset = executor.submit(() -> { resetting.countDown(); return accounts.resetPassword(
          CurrentUser.from(users.findByUsername("admin").orElseThrow()), student.getId()); });
      assertTrue(resetting.await(5, java.util.concurrent.TimeUnit.SECONDS));
      try { assertThrows(java.util.concurrent.TimeoutException.class, () -> reset.get(1, java.util.concurrent.TimeUnit.SECONDS)); }
      finally { release.countDown(); }
      profile.get(5, java.util.concurrent.TimeUnit.SECONDS); reset.get(5, java.util.concurrent.TimeUnit.SECONDS);
    } finally { release.countDown(); }
    var result = users.findById(student.getId()).orElseThrow();
    assertEquals("并发修改后的名字", result.getName());
    assertEquals("计算机", result.getMajor());
    assertNotEquals(initialHash, result.getPasswordHash());
    assertFalse(result.isMustChangePassword());
    assertTrue(passwords.matches("123456", result.getPasswordHash()));
  }

  private MockMultipartFile workbook(Role role, String[][] rows) throws Exception {
    try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
      var sheet = book.createSheet("账号"); var header = sheet.createRow(0);
      String[] columns = role == Role.STUDENT
          ? new String[] {"学号", "姓名", "学校", "学院", "专业", "年级", "指导教师工号"}
          : new String[] {"工号", "姓名", "学校", "学院", "专业"};
      for (int i = 0; i < columns.length; i++) header.createCell(i).setCellValue(columns[i]);
      for (int i = 0; i < rows.length; i++) {
        var row = sheet.createRow(i + 1);
        String[] values = rows[i];
        if (role == Role.STUDENT && values.length <= 4)
          values = new String[] {values[0], values[1], College.SCHOOL_NAME,
              College.INFORMATION_ENGINEERING.displayName(), values.length > 2 ? values[2] : "", "2023",
              values.length > 3 ? values[3] : ""};
        else if (role == Role.TEACHER && values.length <= 2)
          values = new String[] {values[0], values[1], College.SCHOOL_NAME,
              College.INFORMATION_ENGINEERING.displayName(), ""};
        for (int j = 0; j < values.length; j++) row.createCell(j).setCellValue(values[j]);
      }
      book.write(output);
      return new MockMultipartFile("file", "users.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
    }
  }

  @Test void newlyCreatedStudentCanUseSubmissionRouteWithoutChangingPasswordAndMayChangeItVoluntarily() throws Exception {
    var admin = login("admin", "AdminPassword1");
    var adminCsrf = csrf(admin);
    mvc.perform(post("/api/admin/users").session(admin).header(adminCsrf.header(), adminCsrf.token())
        .contentType("application/json").content(json.writeValueAsBytes(Map.of(
            "username", "new_student", "name", "新学生", "role", "STUDENT",
            "grade", 2023, "collegeCode", College.INFORMATION_ENGINEERING.name()))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.mustChangePassword").value(false));
    var session = login("new_student", "123456");
    mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.mustChangePassword").value(false));
    mvc.perform(get("/api/submissions").session(session)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(0));
    var csrf = csrf(session);
    mvc.perform(post("/api/auth/password").session(session).header(csrf.header(), csrf.token())
        .contentType("application/json").content(json.writeValueAsBytes(Map.of("oldPassword", "123456",
            "newPassword", "论文".repeat(50), "confirmPassword", "论文".repeat(50)))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.mustChangePassword").value(false));
    mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    mvc.perform(get("/api/admin/users").session(session)).andExpect(status().isForbidden());
    assertTrue(passwords.matches("论文".repeat(50), users.findByUsername("new_student").orElseThrow().getPasswordHash()));
  }

  @Test void passwordResetInvalidatesExistingSessionsImmediately() throws Exception {
    var studentSession = login("2026001", "123456");
    var admin = login("admin", "AdminPassword1");
    var csrf = csrf(admin);
    mvc.perform(post("/api/admin/users/" + student.getId() + "/reset-password").session(admin)
        .header(csrf.header(), csrf.token())).andExpect(status().isOk());
    mvc.perform(get("/api/auth/me").session(studentSession)).andExpect(status().isUnauthorized());
    var refreshed = login("2026001", "123456");
    mvc.perform(get("/api/auth/me").session(refreshed)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.mustChangePassword").value(false));
    mvc.perform(get("/api/submissions").session(refreshed)).andExpect(status().isOk());
  }

  @Test void teacherPasswordResetStillRequiresChangingTheInitialPassword() throws Exception {
    User teacher = users.save(new User("reset_teacher", passwords.encode("OldPassword1"), Role.TEACHER,
        "教师", null, null, College.INFORMATION_ENGINEERING.name(), false));
    var admin = login("admin", "AdminPassword1");
    var token = csrf(admin);
    mvc.perform(post("/api/admin/users/" + teacher.getId() + "/reset-password")
        .session(admin).header(token.header(), token.token()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.mustChangePassword").value(true));
    var initial = login("reset_teacher", "123456");
    mvc.perform(get("/api/teacher/students/imports/template").session(initial))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("PASSWORD_CHANGE_REQUIRED"));
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

  @Test void collegeAdminIsAssignableSupervisorAndAdminImportAcceptsTheSameAccount() throws Exception {
    User adviser = users.save(new User("advisor_admin", passwords.encode("Password1"), Role.ADMIN, "院管导师", null, null,
        College.INFORMATION_ENGINEERING.name(), false));
    users.save(new User("advisor_teacher", passwords.encode("Password1"), Role.TEACHER, "教师导师", null, null,
        College.INFORMATION_ENGINEERING.name(), false));
    users.save(new User("advisor_other", passwords.encode("Password1"), Role.ADMIN, "跨院院管", null, null,
        College.EDUCATION_ARTS.name(), false));
    User inactive = users.save(new User("advisor_disabled", passwords.encode("Password1"), Role.ADMIN, "停用院管", null, null,
        College.INFORMATION_ENGINEERING.name(), false));
    inactive.setActive(false); users.saveAndFlush(inactive);
    var session = login("admin", "AdminPassword1"); var csrf = csrf(session);
    mvc.perform(get("/api/admin/teachers").session(session).param("collegeCode", College.INFORMATION_ENGINEERING.name())
        .param("q", "advisor"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
        .andExpect(jsonPath("$.data.items[0].username").value("advisor_admin"))
        .andExpect(jsonPath("$.data.items[1].username").value("advisor_teacher"));
    mvc.perform(post("/api/admin/users").session(session).header(csrf.header(), csrf.token())
        .contentType("application/json").content(json.writeValueAsBytes(Map.of("username", "advised_student",
            "name", "指导学生", "role", "STUDENT", "teacherId", adviser.getId().toString(), "grade", 2023,
            "collegeCode", College.INFORMATION_ENGINEERING.name()))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.teacherId").value(adviser.getId().toString()));
    var file = workbook(Role.STUDENT, new String[][] {{"imported_advised", "导入学生", "计算机", "advisor_admin"}});
    mvc.perform(multipart("/api/admin/imports/execute").file(file).param("role", "STUDENT")
        .param("collegeCode", College.INFORMATION_ENGINEERING.name()).session(session).header(csrf.header(), csrf.token()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.successCount").value(1));
    assertEquals(adviser.getId(), users.findByUsername("imported_advised").orElseThrow().getTeacherId());
    mvc.perform(patch("/api/admin/users/" + adviser.getId()).session(session).header(csrf.header(), csrf.token())
        .contentType("application/json").content(json.writeValueAsBytes(Map.of("name", "院管导师",
            "collegeCode", College.EDUCATION_ARTS.name()))))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TEACHER_HAS_STUDENTS"));
    mvc.perform(delete("/api/admin/users/" + adviser.getId()).session(session).header(csrf.header(), csrf.token()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("DEACTIVATED"));
    assertTrue(users.existsById(adviser.getId()));
  }

  private MockHttpSession login(String username, String password) throws Exception {
    var csrf = csrf(null);
    var credentials = new java.util.LinkedHashMap<String, Object>();
    credentials.put("username", username);
    credentials.put("password", password);
    users.findByUsername(username).map(User::getCollegeCode).ifPresent(code -> credentials.put("collegeCode", code));
    return (MockHttpSession) mvc.perform(post("/api/auth/login").session(csrf.session())
        .header(csrf.header(), csrf.token()).contentType("application/json")
        .content(json.writeValueAsBytes(credentials)))
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
