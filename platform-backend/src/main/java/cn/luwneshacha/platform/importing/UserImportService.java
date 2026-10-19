package cn.luwneshacha.platform.importing;

import cn.luwneshacha.platform.account.Role;
import cn.luwneshacha.platform.account.UserRepository;
import cn.luwneshacha.platform.account.UserService;
import cn.luwneshacha.platform.common.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
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
  public UserImportService(ExcelUserFile files, UserRepository users, UserService accounts) {
    this.files = files; this.users = users; this.accounts = accounts;
  }

  // No outer transaction: each UserService.create commits independently so invalid rows cannot undo valid ones.
  public ImportResult process(MultipartFile file, Role role, boolean execute) {
    var rows = files.read(file, role);
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
        String teacherId = null;
        if (!row.teacherUsername().isEmpty()) {
          var teacher = users.findByUsername(row.teacherUsername()).orElseThrow(() ->
              new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEACHER", "指导教师工号不存在"));
          teacherId = teacher.getId().toString();
        }
        var request = new UserService.CreateUser(row.username(), row.name(), role, row.major(), teacherId);
        if (execute) { accounts.create(request); success++; } else accounts.validateNewUser(request);
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
