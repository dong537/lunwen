package cn.luwneshacha.platform.importing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.luwneshacha.platform.account.AdminScope;
import cn.luwneshacha.platform.account.College;
import cn.luwneshacha.platform.account.Role;
import cn.luwneshacha.platform.account.User;
import cn.luwneshacha.platform.account.UserRepository;
import cn.luwneshacha.platform.account.UserService;
import cn.luwneshacha.platform.auth.CurrentUser;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

class AdminStudentImportRequiredFieldsTest {
  private static final String COLLEGE = College.INFORMATION_ENGINEERING.name();
  private static final String[] STUDENT_COLUMNS = {
      "学号", "姓名", "学校", "学院", "专业", "年级", "指导教师工号"};
  private final UserRepository users = mock(UserRepository.class);
  private final UserService accounts = mock(UserService.class);
  private final UserImportService imports = new UserImportService(
      new ExcelUserFile(), users, accounts, new AdminScope(users));
  private CurrentUser admin;

  @BeforeEach void setUp() {
    var actor = user(1L, "admin", Role.ADMIN, null);
    admin = CurrentUser.from(actor);
    when(users.findById(1L)).thenReturn(Optional.of(actor));
    when(users.findByUsername("teacher01"))
        .thenReturn(Optional.of(user(2L, "teacher01", Role.TEACHER, COLLEGE)));
  }

  @ParameterizedTest(name = "execute={0}, missing={1}, blank={2}")
  @MethodSource("missingFields")
  void rejectsEachMissingRequiredFieldAndReportsBothOncePerRow(
      boolean execute, String missing, String blank) throws Exception {
    var values = student("student01");
    if (!missing.equals("teacher")) values[4] = blank;
    if (!missing.equals("major")) values[6] = blank;

    var result = imports.process(admin, workbook(STUDENT_COLUMNS, values), Role.STUDENT, null, execute);

    assertCounts(result, 1, 0, 0, 0, 1);
    var error = result.errors().getFirst();
    assertEquals(2, error.row());
    assertEquals("student01", error.username());
    assertEquals(switch (missing) {
      case "major" -> "请填写专业";
      case "teacher" -> "请填写指导教师工号";
      default -> "请填写专业、指导教师工号";
    }, error.message());
    verifyNoInteractions(accounts);
  }

  static Stream<Arguments> missingFields() {
    return Stream.of(false, true).flatMap(execute -> Stream.of("major", "teacher", "both")
        .flatMap(missing -> Arrays.asList(null, "", " \t\r\n ", "\u3000", "\u00a0", "\u202f", "\u2007")
            .stream().map(blank -> Arguments.of(execute, missing, blank))));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void validatesRequiredFieldsBeforeSkippingAnExistingAccount(boolean execute) throws Exception {
    when(users.existsByUsername("student01")).thenReturn(true);
    var values = student("student01");
    values[4] = null;
    values[6] = null;

    var result = imports.process(admin, workbook(STUDENT_COLUMNS, values), Role.STUDENT, null, execute);

    assertCounts(result, 1, 0, 0, 0, 1);
    assertEquals("请填写专业、指导教师工号", result.errors().getFirst().message());
    verifyNoInteractions(accounts);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void keepsValidRowsAndReportsTheActualExcelRowForInvalidRows(boolean execute) throws Exception {
    var invalid = student("student01");
    invalid[4] = "";
    invalid[6] = "";
    var actor = user(1L, "college_admin", Role.ADMIN, COLLEGE);
    when(users.findById(1L)).thenReturn(Optional.of(actor));

    var result = imports.process(CurrentUser.from(actor),
        workbook(STUDENT_COLUMNS, invalid, student("student02")), Role.STUDENT, COLLEGE, execute);

    assertCounts(result, 2, 1, execute ? 1 : 0, 0, 1);
    assertEquals(2, result.errors().getFirst().row());
    var request = ArgumentCaptor.forClass(UserService.CreateUser.class);
    if (execute) {
      verify(accounts).create(any(), request.capture());
      verify(accounts, never()).validateNewUser(any(), any());
    } else {
      verify(accounts).validateNewUser(any(), request.capture());
      verify(accounts, never()).create(any(), any());
    }
    assertEquals("student02", request.getValue().username());
    assertEquals("计算机科学与技术", request.getValue().major());
    assertEquals("2", request.getValue().teacherId());
    assertEquals(COLLEGE, request.getValue().collegeCode());
    assertEquals(2023, request.getValue().grade());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void stillSkipsAnExistingAccountWhenRequiredFieldsArePresent(boolean execute) throws Exception {
    when(users.existsByUsername("student01")).thenReturn(true);

    var result = imports.process(admin, workbook(STUDENT_COLUMNS, student("student01")),
        Role.STUDENT, null, execute);

    assertCounts(result, 1, 0, 0, 1, 0);
    verifyNoInteractions(accounts);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void countsDuplicateRowsOnlyOncePerRowAndContinuesWithOtherStudents(boolean execute) throws Exception {
    var duplicate = student("student01");
    duplicate[4] = "";
    duplicate[6] = "";

    var result = imports.process(admin,
        workbook(STUDENT_COLUMNS, duplicate, duplicate, student("student02")), Role.STUDENT, null, execute);

    assertCounts(result, 3, 1, execute ? 1 : 0, 0, 2);
    assertEquals(2, result.errors().get(0).row());
    assertEquals(3, result.errors().get(1).row());
    assertTrue(result.errors().stream().allMatch(error -> error.message().contains("重复")));
    if (execute) verify(accounts).create(any(), any());
    else verify(accounts).validateNewUser(any(), any());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void doesNotRequireAMajorOrSupervisorForTeacherAccountImports(boolean execute) throws Exception {
    var file = workbook(new String[] {"工号", "姓名", "学校", "学院", "专业"},
        new String[] {"teacher02", "教师乙", College.SCHOOL_NAME, "信息科学与工程学院", ""});

    var result = imports.process(admin, file, Role.TEACHER, null, execute);

    assertCounts(result, 1, 1, execute ? 1 : 0, 0, 0);
    var request = ArgumentCaptor.forClass(UserService.CreateUser.class);
    if (execute) verify(accounts).create(any(), request.capture());
    else verify(accounts).validateNewUser(any(), request.capture());
    assertEquals(Role.TEACHER, request.getValue().role());
    assertEquals("", request.getValue().major());
    assertNull(request.getValue().teacherId());
  }

  @ParameterizedTest
  @MethodSource("selfImportActors")
  void preservesTeacherAndCollegeAdminSelfImportWithoutMajorOrSupervisorColumn(
      boolean execute, Role actorRole) throws Exception {
    var current = CurrentUser.from(user(3L, "supervisor", actorRole, COLLEGE));
    when(accounts.teacherCollegeCode(current)).thenReturn(COLLEGE);
    var file = workbook(Arrays.copyOf(STUDENT_COLUMNS, 6),
        new String[] {"student01", "学生甲", College.SCHOOL_NAME, "信息科学与工程学院", "", "2023"});

    var result = imports.processTeacherStudents(current, file, execute);

    assertCounts(result, 1, 1, execute ? 1 : 0, 0, 0);
    var request = ArgumentCaptor.forClass(UserService.CreateStudent.class);
    if (execute) verify(accounts).createStudent(eq(current), request.capture());
    else verify(accounts).validateStudent(eq(current), request.capture());
    assertEquals("student01", request.getValue().username());
    assertEquals("", request.getValue().major());
    assertEquals(2023, request.getValue().grade());
    verify(accounts, never()).create(any(), any());
    verify(accounts, never()).validateNewUser(any(), any());
  }

  static Stream<Arguments> selfImportActors() {
    return Stream.of(false, true)
        .flatMap(execute -> Stream.of(Role.TEACHER, Role.ADMIN).map(role -> Arguments.of(execute, role)));
  }

  private static String[] student(String username) {
    return new String[] {username, "学生", College.SCHOOL_NAME, "信息科学与工程学院",
        "计算机科学与技术", "2023", "teacher01"};
  }

  private static User user(long id, String username, Role role, String college) {
    var user = new User(username, "hash", role, "测试账号", null, null, college, false);
    ReflectionTestUtils.setField(user, "id", id);
    return user;
  }

  private static void assertCounts(UserImportService.ImportResult result,
      int total, int valid, int success, int skipped, int failed) {
    assertEquals(total, result.totalRows());
    assertEquals(valid, result.validRows());
    assertEquals(success, result.successCount());
    assertEquals(skipped, result.skippedCount());
    assertEquals(failed, result.failedCount());
    assertEquals(failed, result.errors().size());
  }

  private static MockMultipartFile workbook(String[] columns, String[]... values) throws Exception {
    try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
      var sheet = book.createSheet("账号");
      var header = sheet.createRow(0);
      for (int i = 0; i < columns.length; i++) header.createCell(i).setCellValue(columns[i]);
      for (int rowIndex = 0; rowIndex < values.length; rowIndex++) {
        var row = sheet.createRow(rowIndex + 1);
        for (int column = 0; column < values[rowIndex].length; column++) {
          if (values[rowIndex][column] != null) row.createCell(column).setCellValue(values[rowIndex][column]);
        }
      }
      book.write(output);
      return new MockMultipartFile("file", "accounts.xlsx",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
    }
  }
}
