package cn.luwneshacha.platform.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** All accounts use SHA-256 prehash + BCrypt, so UTF-8 passwords cannot be silently truncated at 72 bytes. */
@Component
public final class PasswordHasher {
  private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(10);
  public String encode(String password) { return bcrypt.encode(prehash(password)); }
  public boolean matches(String password, String encoded) { return bcrypt.matches(prehash(password), encoded); }
  public static String fingerprint(String encoded) { return HexFormat.of().formatHex(sha256(encoded)); }
  private static String prehash(String password) { return Base64.getEncoder().encodeToString(sha256(password)); }
  private static byte[] sha256(String value) {
    try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
    catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is required", impossible); }
  }
}
