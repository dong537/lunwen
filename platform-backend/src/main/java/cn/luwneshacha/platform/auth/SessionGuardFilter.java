package cn.luwneshacha.platform.auth;

import cn.luwneshacha.platform.account.User;
import cn.luwneshacha.platform.account.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Rechecks the database on every authenticated request; session role and password proof are not authoritative. */
final class SessionGuardFilter extends OncePerRequestFilter {
  private static final Set<String> PASSWORD_ROUTES = Set.of("/api/auth/csrf", "/api/auth/me", "/api/auth/password", "/api/auth/logout");
  private final UserRepository users;
  private final SecurityResponses responses;
  SessionGuardFilter(UserRepository users, SecurityResponses responses) { this.users = users; this.responses = responses; }
  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    String path = request.getServletPath().isEmpty() ? request.getRequestURI() : request.getServletPath();
    if (authentication != null && authentication.getPrincipal() instanceof CurrentUser principal) {
      User user = users.findById(principal.id()).orElse(null);
      boolean disabled = user != null && !user.isActive();
      boolean stale = user == null || disabled
          || !PasswordHasher.fingerprint(user.getPasswordHash()).equals(authentication.getDetails());
      if (stale) {
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        if (!path.equals("/api/auth/login") && !path.equals("/api/auth/csrf")) {
          responses.write(response, 401, disabled ? "ACCOUNT_DISABLED" : "SESSION_EXPIRED",
              disabled ? "账号已被禁用，请联系管理员" : "登录已失效，请重新登录");
          return;
        }
      } else {
        SecurityContextHolder.getContext().setAuthentication(SessionAuthentication.forUser(user));
        if (user.isMustChangePassword() && !PASSWORD_ROUTES.contains(path) && !path.equals("/api/auth/login")) {
          responses.write(response, 403, "PASSWORD_CHANGE_REQUIRED", "请先修改初始密码");
          return;
        }
      }
    }
    chain.doFilter(request, response);
  }
}
