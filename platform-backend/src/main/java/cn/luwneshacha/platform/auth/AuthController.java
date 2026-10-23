package cn.luwneshacha.platform.auth;

import cn.luwneshacha.platform.account.UserService;
import cn.luwneshacha.platform.account.UserView;
import cn.luwneshacha.platform.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/auth")
public class AuthController {
  private final AuthenticationManager manager;
  private final AuthService auth;
  private final UserService users;
  private final SecurityContextRepository contexts;
  private final CsrfTokenRepository csrf;
  public AuthController(AuthenticationManager manager, AuthService auth, UserService users,
      SecurityContextRepository contexts, CsrfTokenRepository csrf) {
    this.manager = manager; this.auth = auth; this.users = users; this.contexts = contexts; this.csrf = csrf;
  }
  @GetMapping("/csrf") public ApiResponse<CsrfView> csrf(CsrfToken token) {
    return ApiResponse.ok(new CsrfView(token.getToken(), token.getHeaderName()));
  }
  @PostMapping("/login") public ApiResponse<UserView> login(@Valid @RequestBody Login input,
      HttpServletRequest request, HttpServletResponse response) {
    var authentication = manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(input.username(), input.password()));
    establish(authentication, request, response);
    return ApiResponse.ok(users.current(((CurrentUser) authentication.getPrincipal()).id()));
  }
  @GetMapping("/me") public ApiResponse<UserView> me(@AuthenticationPrincipal CurrentUser user) {
    return ApiResponse.ok(users.current(user.id()));
  }
  @PostMapping("/password") public ApiResponse<UserView> password(@AuthenticationPrincipal CurrentUser current,
      @Valid @RequestBody ChangePassword input, HttpServletRequest request, HttpServletResponse response) {
    var changed = auth.changePassword(current.id(), input.oldPassword(), input.newPassword(), input.confirmPassword());
    establish(SessionAuthentication.forUser(changed), request, response);
    return ApiResponse.ok(users.current(current.id()));
  }
  @PostMapping("/logout") public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
    var session = request.getSession(false);
    if (session != null) session.invalidate();
    SecurityContextHolder.clearContext();
    return ApiResponse.ok(null);
  }
  private void establish(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
    request.getSession(true);
    request.changeSessionId();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    contexts.saveContext(context, request, response);
    csrf.saveToken(null, request, response);
  }
  public record CsrfView(String token, String headerName) {
    @Override public String toString() { return "CsrfView[token=[REDACTED], headerName=" + headerName + "]"; }
  }
  public record Login(@NotNull(message = "请填写账号") @Size(max = 32, message = "账号或密码错误") String username,
      @NotNull(message = "请填写密码") @Size(max = 256, message = "账号或密码错误") String password) {
    @Override public String toString() { return "Login[credentials=[REDACTED]]"; }
  }
  public record ChangePassword(@NotNull(message = "请填写旧密码") @Size(max = 256, message = "旧密码不正确") String oldPassword,
      @NotNull(message = "请填写新密码") @Size(max = 256, message = "新密码须为6至128个字符") String newPassword,
      @NotNull(message = "请再次填写新密码") @Size(max = 256, message = "新密码须为6至128个字符") String confirmPassword) {
    @Override public String toString() { return "ChangePassword[credentials=[REDACTED]]"; }
  }
}
