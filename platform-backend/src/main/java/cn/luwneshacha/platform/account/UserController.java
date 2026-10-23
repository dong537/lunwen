package cn.luwneshacha.platform.account;

import cn.luwneshacha.platform.common.ApiResponse;
import cn.luwneshacha.platform.common.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/admin")
public class UserController {
  private final UserService users;
  public UserController(UserService users) { this.users = users; }
  @GetMapping("/users") public ApiResponse<PageResponse<UserView>> list(@RequestParam Role role,
      @RequestParam(defaultValue = "") String q, @RequestParam(required = false) Boolean active,
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.ok(users.list(role, q, active, page, size));
  }
  @GetMapping("/teachers") public ApiResponse<List<UserView>> teachers(@RequestParam(defaultValue = "") String q) {
    return ApiResponse.ok(users.teachers(q));
  }
  @PostMapping("/users") public ApiResponse<UserView> create(@Valid @RequestBody UserService.CreateUser request) {
    return ApiResponse.ok(users.create(request));
  }
  @PatchMapping("/users/{id}") public ApiResponse<UserView> edit(@PathVariable long id, @Valid @RequestBody UserService.UpdateUser request) {
    return ApiResponse.ok(users.update(id, request));
  }
  @PostMapping("/users/{id}/active") public ApiResponse<UserService.ActiveResult> active(@PathVariable long id, @Valid @RequestBody ActiveRequest request) {
    return ApiResponse.ok(users.setActive(id, request.active()));
  }
  @PostMapping("/users/{id}/reset-password") public ApiResponse<UserView> reset(@PathVariable long id) {
    return ApiResponse.ok(users.resetPassword(id));
  }
  public record ActiveRequest(@NotNull(message = "请选择账号状态") Boolean active) {}
}
