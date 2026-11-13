package cn.luwneshacha.platform.account;

import cn.luwneshacha.platform.auth.CurrentUser;
import cn.luwneshacha.platform.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/teacher/students")
public class TeacherStudentController {
  private final UserService accounts;
  public TeacherStudentController(UserService accounts) { this.accounts = accounts; }
  @PostMapping @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<UserView> create(@AuthenticationPrincipal CurrentUser teacher,
      @Valid @RequestBody UserService.CreateStudent request) {
    return ApiResponse.ok(accounts.createStudent(teacher, request));
  }
}
