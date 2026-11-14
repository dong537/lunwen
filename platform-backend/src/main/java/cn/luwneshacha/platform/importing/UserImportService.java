package cn.luwneshacha.platform.importing;

import cn.luwneshacha.platform.account.Role;
import cn.luwneshacha.platform.account.AdminScope;
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
    String college = scope.college(scope.actor(current), collegeCode, true);
    return processRows(files.read(file, role), execute, row -> {
      String teacherId = null;
      if (!row.teacherUsername().isEmpty()) {
        var teacher = users.findByUsername(row.teacherUsername()).orElseThrow(() ->
            new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEACHER", "指导教师工号不存在"));
        teacherId = teacher.getId().toString();
      }
      var request = new UserService.CreateUser(row.username(), row.name(), role, row.major(), teacherId, college);
      if (execute) accounts.create(current, request); else accounts.validateNewUser(current, request);
    });
  }

  public ImportResult processTeacherStudents(CurrentUser current, MultipartFile file, boolean execute) {
    accounts.validateTeacher(current);
    return processRows(files.readTeacherStudents(file), execute, row -> {
      var request = new UserService.CreateStudent(row.username(), row.name(), row.major());
      if (execute) accounts.createStudent(current, request); else accounts.validateStudent(current, request);
    });
  }

  private ImportResult processRows(List<ExcelUserFile.RowInput> rows, boolean execute, Consumer<ExcelUserFile.RowInput> action) {
    var counts = rows.stream().map(ExcelUserFile.RowInput::username).collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    var errors = new ArrayList<RowError>();
    int valid = 0, success = 0, skipped = 0;
    for (var row : rows) {
      if (row.error() != null) { errors.add(new RowError(row.row(), row.username(), row.error())); continue; }
      if (!row.username().isEmpty() && counts.get(row.username()) > 1) {
        errors.add(new RowError(row.row(), row.username(), "账号在当前文件中重复，请每个账号只保留一行")); continue;
      }
      if (users.existsByUsername(row.username())) { skipped++; continue; }
      try {
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
