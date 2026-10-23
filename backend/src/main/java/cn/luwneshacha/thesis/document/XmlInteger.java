package cn.luwneshacha.thesis.document;

import java.math.BigInteger;

/** Exact conversion for integer-valued OOXML attributes. */
final class XmlInteger {
  private static final BigInteger MIN = BigInteger.valueOf(Integer.MIN_VALUE);
  private static final BigInteger MAX = BigInteger.valueOf(Integer.MAX_VALUE);

  private XmlInteger() {}

  static Integer exact(BigInteger value) {
    if (value == null || value.compareTo(MIN) < 0 || value.compareTo(MAX) > 0) return null;
    return value.intValue();
  }

  static Integer parse(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return exact(new BigInteger(value.trim()));
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  static boolean isInteger(String value) {
    return value != null && !value.isBlank() && value.trim().matches("[+-]?\\d+");
  }
}
