package cn.luwneshacha.platform.account;

import cn.luwneshacha.platform.auth.PasswordHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "platform.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
public class AdministratorBootstrap implements ApplicationRunner {
  private final UserRepository users;
  private final PasswordHasher passwords;
  private final String username;
  private final String password;
  public AdministratorBootstrap(UserRepository users, PasswordHasher passwords,
      @Value("${platform.bootstrap.username}") String username, @Value("${platform.bootstrap.password}") String password) {
    this.users = users; this.passwords = passwords; this.username = username; this.password = password;
  }
  @Override @Transactional public void run(ApplicationArguments args) {
    if (users.existsByRole(Role.ADMIN)) return;
    if (!username.matches("[A-Za-z0-9_]{3,32}") || password.codePointCount(0, password.length()) < 6
        || password.codePointCount(0, password.length()) > 128)
      throw new IllegalStateException("首次启动必须配置 INITIAL_ADMIN_USERNAME 与6至128字符的 INITIAL_ADMIN_PASSWORD");
    if (users.existsByUsername(username)) throw new IllegalStateException("初始管理员账号与现有账号冲突");
    users.saveAndFlush(new User(username, passwords.encode(password), Role.ADMIN, "管理员", null, null, false));
  }
}
