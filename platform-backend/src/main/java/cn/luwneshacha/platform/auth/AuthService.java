package cn.luwneshacha.platform.auth;

import cn.luwneshacha.platform.account.User;
import cn.luwneshacha.platform.account.UserRepository;
import cn.luwneshacha.platform.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private final UserRepository users;
  private final PasswordHasher passwords;
  private final String dummyHash;
  public AuthService(UserRepository users, PasswordHasher passwords) {
    this.users = users; this.passwords = passwords; this.dummyHash = passwords.encode(java.util.UUID.randomUUID().toString());
  }
  @Transactional(readOnly = true)
  public User login(String username, String password) {
    if (username == null || username.length() > 32 || password == null || password.codePointCount(0, password.length()) > 128)
      throw credentialsError();
    User user = users.findByUsername(username).orElse(null);
    boolean matches = passwords.matches(password, user == null ? dummyHash : user.getPasswordHash());
    if (user == null || !matches) throw credentialsError();
    if (!user.isActive()) throw new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED", "账号已被禁用，请联系管理员");
    return user;
  }
  @Transactional
  public User changePassword(long id, String oldPassword, String newPassword, String confirmPassword) {
    User user = users.findByIdForUpdate(id).orElseThrow(AuthService::credentialsError);
    if (!user.isActive()) throw new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED", "账号已被禁用，请联系管理员");
    if (newPassword == null || newPassword.codePointCount(0, newPassword.length()) < 6
        || newPassword.codePointCount(0, newPassword.length()) > 128)
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "新密码须为6至128个字符");
    if (!newPassword.equals(confirmPassword))
      throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_MISMATCH", "两次输入的新密码不一致");
    if (oldPassword == null || !passwords.matches(oldPassword, user.getPasswordHash()))
      throw new ApiException(HttpStatus.BAD_REQUEST, "OLD_PASSWORD_INCORRECT", "旧密码不正确");
    if (passwords.matches(newPassword, user.getPasswordHash()))
      throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_UNCHANGED", "新密码不能与旧密码相同");
    user.changePassword(passwords.encode(newPassword), false);
    return users.saveAndFlush(user);
  }
  private static ApiException credentialsError() {
    return new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "账号或密码错误");
  }
}
