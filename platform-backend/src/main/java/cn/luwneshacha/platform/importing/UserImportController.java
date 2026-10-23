package cn.luwneshacha.platform.importing;

import cn.luwneshacha.platform.account.Role;
import cn.luwneshacha.platform.common.ApiResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/admin/imports")
public class UserImportController {
  private final ExcelUserFile files;
  private final UserImportService imports;
  public UserImportController(ExcelUserFile files, UserImportService imports) { this.files = files; this.imports = imports; }
  @GetMapping("/template") public ResponseEntity<byte[]> template(@RequestParam Role role) {
    byte[] content = files.template(role);
    String filename = role == Role.STUDENT ? "学生账号导入模板.xlsx" : "教师账号导入模板.xlsx";
    return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
        .body(content);
  }
  @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<UserImportService.ImportResult> preview(@RequestParam MultipartFile file, @RequestParam Role role) {
    return ApiResponse.ok(imports.process(file, role, false));
  }
  @PostMapping(value = "/execute", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<UserImportService.ImportResult> execute(@RequestParam MultipartFile file, @RequestParam Role role) {
    return ApiResponse.ok(imports.process(file, role, true));
  }
}
