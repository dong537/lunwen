package cn.luwneshacha.thesis.document;

import java.util.Arrays;

public record RawPart(String name, String sha256, byte[] bytes, boolean xml) {
  public RawPart {
    if (name == null || sha256 == null || bytes == null) throw new IllegalArgumentException("raw part fields are required");
    bytes = bytes.clone();
  }

  @Override public byte[] bytes() { return bytes.clone(); }
}