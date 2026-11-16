package cn.luwneshacha.platform.importing;

import cn.luwneshacha.platform.account.Role;
import cn.luwneshacha.platform.account.AdminScope;
import cn.luwneshacha.platform.account.College;
import cn.luwneshacha.platform.account.UserRepository;
import cn.luwneshacha.platform.account.UserService;
import cn.luwneshacha.platform.auth.CurrentUser;
import cn.luwneshacha.platform.common.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class UserImportService {
  private final ExcelUserFile files;
  private final UserRepository users;
  private final UserService accounts;
  private final AdminScope scope;
  public UserImportService(ExcelUserFile files, UserRepository users, UserService accounts, AdminScope scope) {
    this.files = files; this.users = users; this.accounts = accounts; this.scope = scope;
  }

  // No outer transaction: each UserService.create commits independently so invalid rows cannot undo valid ones.
  public ImportResult process(CurrentUser current, MultipartFile file, Role role, String collegeCode, boolean execute) {
    if (role != Role.STUDENT && role != Role.TEACHER)
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ROLE", "名单只能导入学生或教师账号");
    scope.college(scope.actor(current), collegeCode, false);
    return processRows(files.read(file, role), execute, row -> {
      String allowed = scope.college(scope.actor(current), collegeCode, false);
      String rowCollege = validateLocation(row);
      if (allowed != null && !allowed.equals(rowCollege)) throw invalidCollege();
      if (role == Role.STUDENT) parseGrade(row.grade());
    }, row -> {
      String rowCollege = College.fromDisplayName(row.college()).name();
      String teacherId = null;
      if (!row.teacherUsername().isEmpty()) {
        var teacher = users.findByUsername(row.teacherUsername()).orElseThrow(() ->
            new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEACHER", "指导教师工号不存在"));
        teacherId = teacher.getId().toString();
      }
      var request = new UserService.CreateUser(row.username(), row.name(), role, row.major(), teacherId, rowCollege,
          role == Role.STUDENT ? parseGrade(row.grade()) : null);
      if (execute) accounts.create(current, request); else accounts.validateNewUser(current, request);
    });
  }

  public ImportResult processTeacherStudents(CurrentUser current, MultipartFile file, boolean execute) {
    accounts.teacherCollegeCode(current);
    return processRows(files.readTeacherStudents(file), execute, row -> {
      if (!accounts.teacherCollegeCode(current).equals(validateLocation(row))) throw invalidCollege();
      parseGrade(row.grade());
    }, row -> {
      var request = new UserService.CreateStudent(row.username(), row.name(), row.major(), parseGrade(row.grade()));
      if (execute) accounts.createStudent(current, request); else accounts.validateStudent(current, request);
    });
  }

  private static String validateLocation(ExcelUserFile.RowInput row) {
    if (!College.SCHOOL_NAME.equals(row.school()))
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SCHOOL", "学校须填写“" + College.SCHOOL_NAME + "”");
    return College.fromDisplayName(row.college()).name();
  }
  private static ApiException invalidCollege() {
    return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_COLLEGE", "该行学院不在当前导入范围内");
  }
  private static Integer parseGrade(String value) {
    if (value == null || value.isBlank())
      throw new ApiException(HttpStatus.BAD_REQUEST, "GRADE_REQUIRED", "请填写学生入学年级");
    String year = value.endsWith("年") ? value.substring(0, value.length() - 1) : value;
    if (!year.matches("[0-9]{4}"))
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GRADE", "年级请填写2000至2100年的四位入学年份");
    int grade = Integer.parseInt(year);
    if (grade < 2000 || grade > 2100)
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GRADE", "年级请填写2000至2100年的四位入学年份");
    return grade;
  }

  private ImportResult processRows(List<ExcelUserFile.RowInput> rows, boolean execute,
      Consumer<ExcelUserFile.RowInput> preflight, Consumer<ExcelUserFile.RowInput> action) {
    var counts = rows.stream().map(ExcelUserFile.RowInput::username).collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    var errors = new ArrayList<RowError>();
    int valid = 0, success = 0, skipped = 0;
    for (var row : rows) {
      if (row.error() != null) { errors.add(new RowError(row.row(), row.username(), row.error())); continue; }
      if (!row.username().isEmpty() && counts.get(row.username()) > 1) {
        errors.add(new RowError(row.row(), row.username(), "账号在当前文件中重复，请每个账号只保留一行")); continue;
      }
      try {
        preflight.accept(row);
        if (users.existsByUsername(row.username())) { skipped++; continue; }
        action.accept(row);
        if (execute) success++;
        valid++;
      } catch (ApiException e) {
        if (e.code().equals("USERNAME_EXISTS")) skipped++;
        else if (e.status() == HttpStatus.BAD_REQUEST) errors.add(new RowError(row.row(), row.username(), e.getMessage()));
        else throw e;
      } catch (DataIntegrityViolationException e) {
        // A concurrent import may create the same account between validation and insertion.
        if (users.existsByUsername(row.username())) skipped++; else throw e;
      }
    }
    return new ImportResult(rows.size(), valid, success, skipped, errors.size(), List.copyOf(errors));
  }
  public record RowError(int row, String username, String message) {}
  public record ImportResult(int totalRows, int validRows, int successCount, int skippedCount, int failedCount, List<RowError> errors) {}
}
