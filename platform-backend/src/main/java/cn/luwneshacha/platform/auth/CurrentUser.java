package cn.luwneshacha.platform.auth;

import cn.luwneshacha.platform.account.Role;
import cn.luwneshacha.platform.account.User;
import java.io.Serializable;

public record CurrentUser(long id, String username, Role role, String name, boolean mustChangePassword) implements Serializable {
  public static CurrentUser from(User user) {
    return new CurrentUser(user.getId(), user.getUsername(), user.getRole(), user.getName(), user.isMustChangePassword());
  }
}
