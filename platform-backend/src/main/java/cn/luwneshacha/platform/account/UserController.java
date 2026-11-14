package cn.luwneshacha.platform.account;

import cn.luwneshacha.platform.common.ApiResponse;
import cn.luwneshacha.platform.common.PageResponse;
import cn.luwneshacha.platform.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/admin")
public class UserController {
  private final UserService users;
  public UserController(UserService users) { this.users = users; }
  @GetMapping("/users") public ApiResponse<PageResponse<UserView>> list(@AuthenticationPrincipal CurrentUser current, @RequestParam Role role,
      @RequestParam(defaultValue = "") String q, @RequestParam(required = false) Boolean active,
      @RequestParam(required = false) String collegeCode,
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.ok(users.list(current, role, q, active, collegeCode, page, size));
  }
  @GetMapping("/teachers") public ApiResponse<List<UserView>> teachers(@AuthenticationPrincipal CurrentUser current,
      @RequestParam(defaultValue = "") String q, @RequestParam String collegeCode) {
    return ApiResponse.ok(users.teachers(current, q, collegeCode));
  }
  @PostMapping("/users") public ApiResponse<UserView> create(@AuthenticationPrincipal CurrentUser current,
      @Valid @RequestBody UserService.CreateUser request) {
    return ApiResponse.ok(users.create(current, request));
  }
  @PatchMapping("/users/{id}") public ApiResponse<UserView> edit(@AuthenticationPrincipal CurrentUser current,
      @PathVariable long id, @Valid @RequestBody UserService.UpdateUser request) {
    return ApiResponse.ok(users.update(current, id, request));
  }
  @PostMapping("/users/{id}/active") public ApiResponse<UserService.ActiveResult> active(@AuthenticationPrincipal CurrentUser current,
      @PathVariable long id, @Valid @RequestBody ActiveRequest request) {
    return ApiResponse.ok(users.setActive(current, id, request.active()));
  }
  @PostMapping("/users/{id}/reset-password") public ApiResponse<UserView> reset(@AuthenticationPrincipal CurrentUser current, @PathVariable long id) {
    return ApiResponse.ok(users.resetPassword(current, id));
  }
  @DeleteMapping("/users/{id}") public ApiResponse<UserService.DeleteResult> delete(@AuthenticationPrincipal CurrentUser current, @PathVariable long id) {
    return ApiResponse.ok(users.delete(current, id));
  }
  public record ActiveRequest(@NotNull(message = "请选择账号状态") Boolean active) {}
}
