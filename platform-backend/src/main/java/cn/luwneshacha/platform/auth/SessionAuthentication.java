package cn.luwneshacha.platform.auth;

import cn.luwneshacha.platform.account.User;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

final class SessionAuthentication {
  private SessionAuthentication() {}
  static UsernamePasswordAuthenticationToken forUser(User user) {
    var token = UsernamePasswordAuthenticationToken.authenticated(CurrentUser.from(user), null,
        AuthorityUtils.createAuthorityList("ROLE_" + user.getRole().name()));
    token.setDetails(PasswordHasher.fingerprint(user.getPasswordHash()));
    return token;
  }
}
