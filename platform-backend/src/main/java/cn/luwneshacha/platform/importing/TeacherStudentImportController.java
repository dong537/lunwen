package cn.luwneshacha.platform.importing;

import cn.luwneshacha.platform.account.UserService;
import cn.luwneshacha.platform.auth.CurrentUser;
import cn.luwneshacha.platform.common.ApiResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/teacher/students/imports")
public class TeacherStudentImportController {
  private final ExcelUserFile files;
  private final UserImportService imports;
  private final UserService accounts;
  public TeacherStudentImportController(ExcelUserFile files, UserImportService imports, UserService accounts) {
    this.files = files; this.imports = imports; this.accounts = accounts;
  }
  @GetMapping("/template")
  public ResponseEntity<byte[]> template(@AuthenticationPrincipal CurrentUser teacher) {
    accounts.validateTeacher(teacher);
    return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
            .filename("教师学生导入模板.xlsx", StandardCharsets.UTF_8).build().toString())
        .body(files.teacherStudentTemplate());
  }
  @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<UserImportService.ImportResult> preview(@AuthenticationPrincipal CurrentUser teacher,
      @RequestParam MultipartFile file) {
    return ApiResponse.ok(imports.processTeacherStudents(teacher, file, false));
  }
  @PostMapping(value = "/execute", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<UserImportService.ImportResult> execute(@AuthenticationPrincipal CurrentUser teacher,
      @RequestParam MultipartFile file) {
    return ApiResponse.ok(imports.processTeacherStudents(teacher, file, true));
  }
}
