package cn.luwneshacha.platform.auth;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PasswordHasherTest {
  @Test
  void distinguishesLongChinesePasswordsAfterTheBcryptByteBoundary() {
    var hasher = new PasswordHasher();
    String prefix = "论文".repeat(45);
    String encoded = hasher.encode(prefix + "甲");
    assertTrue(hasher.matches(prefix + "甲", encoded));
    assertFalse(hasher.matches(prefix + "乙", encoded));
    assertTrue(encoded.length() <= 72);
  }

  @Test
  void saltsIdenticalPasswordsSoResetRevokesOldSessions() {
    var hasher = new PasswordHasher();
    String first = hasher.encode("123456");
    String second = hasher.encode("123456");
    assertNotEquals(PasswordHasher.fingerprint(first), PasswordHasher.fingerprint(second));
    assertTrue(hasher.matches("123456", second));
  }

  @Test void credentialDtosDoNotExposeSecretsToDiagnosticLogging() {
    assertFalse(new AuthController.Login("private-user", "private-password").toString().contains("private"));
    assertFalse(new AuthController.ChangePassword("old-secret", "new-secret", "new-secret").toString().contains("secret"));
    assertFalse(new AuthController.CsrfView("private-token", "X-CSRF-TOKEN").toString().contains("private-token"));
  }
}
