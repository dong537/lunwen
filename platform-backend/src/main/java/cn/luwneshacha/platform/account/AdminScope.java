package cn.luwneshacha.platform.account;

import cn.luwneshacha.platform.auth.CurrentUser;
import cn.luwneshacha.platform.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** The database, not the session or request body, owns administrator college scope. */
@Component
public class AdminScope {
  private final UserRepository users;
  public AdminScope(UserRepository users) { this.users = users; }
  public User actor(CurrentUser current) {
    return checked(current, false);
  }
  public User actorForWrite(CurrentUser current) {
    return checked(current, true);
  }
  private User checked(CurrentUser current, boolean lock) {
    if (current == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录");
    User actor = (lock ? users.findByIdForUpdate(current.id()) : users.findById(current.id()))
        .orElseThrow(AdminScope::forbidden);
    if (actor.getRole() != Role.ADMIN || !actor.isActive() || actor.isMustChangePassword()) throw forbidden();
    return actor;
  }
  public String college(User actor, String requested, boolean required) {
    if (actor.getCollegeCode() != null) {
      if (requested != null && !requested.isBlank() && !actor.getCollegeCode().equals(requested)) throw forbidden();
      return actor.getCollegeCode();
    }
    if (requested == null || requested.isBlank()) {
      if (required) College.fromCode(requested);
      return null;
    }
    return College.fromCode(requested).name();
  }
  public void target(User actor, User target) {
    if (actor.getCollegeCode() != null) {
      if (target.getRole() == Role.ADMIN || !actor.getCollegeCode().equals(target.getCollegeCode())) throw forbidden();
    } else if (target.getRole() == Role.ADMIN && target.getCollegeCode() == null) {
      throw forbidden();
    }
  }
  public void school(User actor) {
    if (actor.getCollegeCode() != null) throw forbidden();
  }
  public static ApiException forbidden() {
    return new ApiException(HttpStatus.FORBIDDEN, "COLLEGE_SCOPE_FORBIDDEN", "无权访问其他学院的账号");
  }
}
